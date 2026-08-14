package abomination;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.mojang.logging.LogUtils;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.openhft.hashing.LongHashFunction;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class LinearRegionFile implements IRegionFile {
    private static final long SUPERBLOCK = 0xc3ff13183cca9d9aL;
    private static final byte VERSION = 3;
    private static final int HEADER_SIZE = 27;
    private static final int FOOTER_SIZE = 8;
    private static final Logger LOGGER = LogUtils.getLogger();

    private byte[][] bucketBuffers;
    private final byte[][] buffer = new byte[1024][];
    private final int[] bufferUncompressedSize = new int[1024];

    private final long[] chunkTimestamps = new long[1024];
    private final Object markedToSaveLock = new Object();

    private final LZ4Compressor compressor;
    private final LZ4FastDecompressor decompressor;

    private boolean markedToSave = false;
    private boolean close = false;

    private volatile boolean flushQueued = false;

    public final ReentrantLock fileLock = new ReentrantLock(true);
    public Path regionFile;

    private final int compressionLevel;
    private int gridSize = 8;
    private int bucketSize = 4;

    public Path getRegionFile() {
        return this.regionFile;
    }

    public ReentrantLock getFileLock() {
        return this.fileLock;
    }

    private int chunkToBucketIdx(int chunkX, int chunkZ) {
        int bx = chunkX / bucketSize, bz = chunkZ / bucketSize;
        return bx * gridSize + bz;
    }

    private static void requireRemaining(ByteBuffer buffer, int length, String description) throws IOException {
        if (length < 0 || buffer.remaining() < length) {
            throw new IOException("Truncated " + description + ": need " + length + " bytes, have " + buffer.remaining());
        }
    }

    private void openBucket(int chunkX, int chunkZ) throws IOException {
        chunkX = Math.floorMod(chunkX, 32);
        chunkZ = Math.floorMod(chunkZ, 32);
        int idx = chunkToBucketIdx(chunkX, chunkZ);

        if (bucketBuffers == null) return;
        if (bucketBuffers[idx] != null) {
            try {
                ByteArrayInputStream bucketByteStream = new ByteArrayInputStream(bucketBuffers[idx]);
                ZstdInputStream zstdStream = new ZstdInputStream(bucketByteStream);
                ByteBuffer bucketBuffer = ByteBuffer.wrap(zstdStream.readAllBytes());

                byte[][] loadedChunks = new byte[bucketSize * bucketSize][];
                int[] loadedSizes = new int[bucketSize * bucketSize];
                long[] loadedTimestamps = new long[bucketSize * bucketSize];
                int[] loadedIndexes = new int[bucketSize * bucketSize];
                int loadedCount = 0;

                int bx = chunkX / bucketSize, bz = chunkZ / bucketSize;

                for (int cx = 0; cx < 32 / gridSize; cx++) {
                    for (int cz = 0; cz < 32 / gridSize; cz++) {
                        int chunkIndex = (bx * (32 / gridSize) + cx) + (bz * (32 / gridSize) + cz) * 32;

                        requireRemaining(bucketBuffer, Integer.BYTES + Long.BYTES, "Linear bucket entry");
                        int chunkSize = bucketBuffer.getInt();
                        long timestamp = bucketBuffer.getLong();

                        if (chunkSize < 0 || (chunkSize > 0 && chunkSize < Long.BYTES)) {
                            throw new IOException("Invalid chunk size " + chunkSize + " in " + this.regionFile + " bucket " + idx);
                        }

                        loadedIndexes[loadedCount] = chunkIndex;
                        loadedTimestamps[loadedCount] = timestamp;

                        if (chunkSize > 0) {
                            int payloadSize = chunkSize - Long.BYTES;
                            requireRemaining(bucketBuffer, payloadSize, "Linear bucket chunk payload");
                            byte[] chunkData = new byte[payloadSize];
                            bucketBuffer.get(chunkData);

                            int maxCompressedLength = this.compressor.maxCompressedLength(chunkData.length);
                            byte[] compressed = new byte[maxCompressedLength];
                            int compressedLength = this.compressor.compress(chunkData, 0, chunkData.length, compressed, 0, maxCompressedLength);
                            byte[] finalCompressed = new byte[compressedLength];
                            System.arraycopy(compressed, 0, finalCompressed, 0, compressedLength);

                            loadedChunks[loadedCount] = finalCompressed;
                            loadedSizes[loadedCount] = chunkData.length;
                        }
                        loadedCount++;
                    }
                }

                if (bucketBuffer.hasRemaining()) {
                    throw new IOException("Trailing data in " + this.regionFile + " bucket " + idx + ": " + bucketBuffer.remaining() + " bytes");
                }

                for (int i = 0; i < loadedCount; i++) {
                    int chunkIndex = loadedIndexes[i];
                    this.buffer[chunkIndex] = loadedChunks[i];
                    this.bufferUncompressedSize[chunkIndex] = loadedSizes[i];
                    this.chunkTimestamps[chunkIndex] = loadedTimestamps[i];
                }
            } catch (IOException | RuntimeException ex) {
                throw new IOException("Region file corrupted: " + regionFile + " bucket: " + idx, ex);
            }
            bucketBuffers[idx] = null;
        }
    }

    public boolean regionFileOpen = false;

    private synchronized void openRegionFile() throws IOException {
        if (regionFileOpen) return;

        if (Files.notExists(this.regionFile)) {
            regionFileOpen = true;
            return;
        }

        try {
            byte[] fileContent = Files.readAllBytes(this.regionFile);
            ByteBuffer buffer = ByteBuffer.wrap(fileContent);

            requireRemaining(buffer, Long.BYTES + Byte.BYTES, "Linear header");
            long superBlock = buffer.getLong();
            if (superBlock != SUPERBLOCK)
                throw new IOException("Invalid superblock: " + superBlock + " file " + this.regionFile);

            byte version = buffer.get();
            if (version == 1 || version == 2) {
                parseLinearV1(buffer);
            } else if (version == 3) {
                parseLinearV2(buffer);
            } else {
                throw new IOException("Invalid version: " + version + " file " + this.regionFile);
            }
            regionFileOpen = true;
        } catch (IOException e) {
            resetLoadedState();
            throw new IOException("Failed to open region file " + this.regionFile, e);
        } catch (BufferUnderflowException | NegativeArraySizeException | IndexOutOfBoundsException e) {
            resetLoadedState();
            throw new IOException("Corrupt Linear region file " + this.regionFile, e);
        }
    }

    private void resetLoadedState() {
        Arrays.fill(this.buffer, null);
        Arrays.fill(this.bufferUncompressedSize, 0);
        Arrays.fill(this.chunkTimestamps, 0L);
        this.bucketBuffers = null;
        this.gridSize = 8;
        this.bucketSize = 4;
        this.regionFileOpen = false;
    }

    private void parseLinearV1(ByteBuffer buffer) throws IOException {
        final int HEADER_SIZE = 32;
        final int FOOTER_SIZE = 8;

        requireRemaining(buffer, 11 + Integer.BYTES + Long.BYTES, "legacy Linear header");
        buffer.getLong();
        buffer.get();
        int declaredChunkCount = Short.toUnsignedInt(buffer.getShort());

        int dataCount = buffer.getInt();
        if (dataCount < 0) {
            throw new IOException("Compressed region length exceeds signed Java int: " + Integer.toUnsignedLong(dataCount) + " in " + this.regionFile);
        }
        long fileLength = Files.size(this.regionFile);
        long expectedFileLength = (long) HEADER_SIZE + dataCount + FOOTER_SIZE;
        if (fileLength != expectedFileLength) {
            throw new IOException("Invalid file length: " + this.regionFile + " " + fileLength + " " + expectedFileLength);
        }

        buffer.getLong();

        requireRemaining(buffer, dataCount, "legacy Linear compressed payload");
        byte[] rawCompressed = new byte[dataCount];
        buffer.get(rawCompressed);

        requireRemaining(buffer, FOOTER_SIZE, "legacy Linear footer");
        long footerSuperBlock = buffer.getLong();
        if (footerSuperBlock != SUPERBLOCK || buffer.hasRemaining()) {
            throw new IOException("Invalid legacy Linear footer: " + this.regionFile);
        }

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(rawCompressed);
        ZstdInputStream zstdInputStream = new ZstdInputStream(byteArrayInputStream);
        ByteBuffer decompressedBuffer = ByteBuffer.wrap(zstdInputStream.readAllBytes());

        int[] starts = new int[1024];
        long[] timestamps = new long[1024];
        long totalChunkBytes = 0L;
        int actualChunkCount = 0;
        requireRemaining(decompressedBuffer, 1024 * 8, "legacy Linear chunk table");
        for (int i = 0; i < 1024; i++) {
            starts[i] = decompressedBuffer.getInt();
            timestamps[i] = Integer.toUnsignedLong(decompressedBuffer.getInt());
            if (starts[i] < 0) {
                throw new IOException("Chunk " + i + " length exceeds signed Java int: " + Integer.toUnsignedLong(starts[i]) + " in " + this.regionFile);
            }
            if (starts[i] > 0) {
                totalChunkBytes += starts[i];
                actualChunkCount++;
            }
        }

        if (declaredChunkCount != actualChunkCount) {
            throw new IOException("Chunk count mismatch in " + this.regionFile + ": header " + declaredChunkCount + ", table " + actualChunkCount);
        }
        if (totalChunkBytes != decompressedBuffer.remaining()) {
            throw new IOException("Chunk payload length mismatch in " + this.regionFile + ": table " + totalChunkBytes + ", payload " + decompressedBuffer.remaining());
        }

        byte[][] loadedBuffer = new byte[1024][];
        int[] loadedSizes = new int[1024];
        for (int i = 0; i < 1024; i++) {
            if (starts[i] > 0) {
                int size = starts[i];
                byte[] chunkData = new byte[size];
                decompressedBuffer.get(chunkData);

                int maxCompressedLength = this.compressor.maxCompressedLength(size);
                byte[] compressed = new byte[maxCompressedLength];
                int compressedLength = this.compressor.compress(chunkData, 0, size, compressed, 0, maxCompressedLength);
                byte[] finalCompressed = new byte[compressedLength];
                System.arraycopy(compressed, 0, finalCompressed, 0, compressedLength);

                loadedBuffer[i] = finalCompressed;
                loadedSizes[i] = size;
            }
        }

        System.arraycopy(loadedBuffer, 0, this.buffer, 0, 1024);
        System.arraycopy(loadedSizes, 0, this.bufferUncompressedSize, 0, 1024);
        System.arraycopy(timestamps, 0, this.chunkTimestamps, 0, 1024);
    }

    private void parseLinearV2(ByteBuffer buffer) throws IOException {
        requireRemaining(buffer, Long.BYTES + Byte.BYTES + 2 * Integer.BYTES + 128, "bucketed Linear header");
        buffer.getLong();
        int parsedGridSize = Byte.toUnsignedInt(buffer.get());
        if (parsedGridSize != 1 && parsedGridSize != 2 && parsedGridSize != 4 && parsedGridSize != 8 && parsedGridSize != 16 && parsedGridSize != 32)
            throw new IOException("Invalid grid size: " + parsedGridSize + " file " + this.regionFile);
        int parsedBucketSize = 32 / parsedGridSize;

        buffer.getInt();
        buffer.getInt();

        deserializeExistenceBitmap(buffer);

        while (true) {
            requireRemaining(buffer, 1, "Linear feature name length");
            int featureNameLength = Byte.toUnsignedInt(buffer.get());
            if (featureNameLength == 0) break;
            requireRemaining(buffer, featureNameLength + Integer.BYTES, "Linear feature");
            byte[] featureNameBytes = new byte[featureNameLength];
            buffer.get(featureNameBytes);
            buffer.getInt();
        }

        int bucketCount = parsedGridSize * parsedGridSize;
        requireRemaining(buffer, bucketCount * (Integer.BYTES + Byte.BYTES + Long.BYTES) + Long.BYTES, "Linear bucket table");
        int[] bucketSizes = new int[bucketCount];
        long[] bucketHashes = new long[bucketCount];
        long totalBucketBytes = 0L;
        for (int i = 0; i < bucketCount; i++) {
            bucketSizes[i] = buffer.getInt();
            if (bucketSizes[i] < 0) {
                throw new IOException("Negative bucket size " + bucketSizes[i] + " in " + this.regionFile);
            }
            buffer.get();
            bucketHashes[i] = buffer.getLong();
            totalBucketBytes += bucketSizes[i];
        }

        if (totalBucketBytes + Long.BYTES != buffer.remaining()) {
            throw new IOException("Bucket payload length mismatch in " + this.regionFile + ": table " + totalBucketBytes + ", payload " + (buffer.remaining() - Long.BYTES));
        }

        byte[][] parsedBucketBuffers = new byte[bucketCount][];
        for (int i = 0; i < bucketCount; i++) {
            if (bucketSizes[i] > 0) {
                parsedBucketBuffers[i] = new byte[bucketSizes[i]];
                buffer.get(parsedBucketBuffers[i]);
                long rawHash = LongHashFunction.xx().hashBytes(parsedBucketBuffers[i]);
                if (rawHash != bucketHashes[i]) throw new IOException("Region file hash incorrect " + this.regionFile);
            }
        }

        long footerSuperBlock = buffer.getLong();
        if (footerSuperBlock != SUPERBLOCK || buffer.hasRemaining())
            throw new IOException("Footer superblock invalid " + this.regionFile);

        this.gridSize = parsedGridSize;
        this.bucketSize = parsedBucketSize;
        this.bucketBuffers = parsedBucketBuffers;
    }

    public LinearRegionFile(RegionStorageInfo storageKey, Path directory, Path path, boolean dsync, int compressionLevel) throws IOException {
        this(storageKey, directory, path, RegionFileVersion.getSelected(), dsync, compressionLevel);
    }

    public LinearRegionFile(RegionStorageInfo storageKey, Path path, Path directory, RegionFileVersion compressionFormat, boolean dsync, int compressionLevel) throws IOException {
        this.regionFile = path;
        this.compressionLevel = compressionLevel;

        this.compressor = LZ4Factory.fastestInstance().fastCompressor();
        this.decompressor = LZ4Factory.fastestInstance().fastDecompressor();
    }

    private void markToSave() {
        synchronized (markedToSaveLock) {
            markedToSave = true;
        }
    }

    private boolean isMarkedToSave() {
        synchronized (markedToSaveLock) {
            if (markedToSave) {
                markedToSave = false;
                return true;
            }
            return false;
        }
    }

    private boolean hasPendingSave() {
        synchronized (markedToSaveLock) {
            return markedToSave;
        }
    }

    public static int SAVE_THREAD_MAX_COUNT = 6;
    public static int SAVE_DELAY_MS = 100;
    public static boolean USE_VIRTUAL_THREAD = true;

    private static final Set<LinearRegionFile> pendingFlush = ConcurrentHashMap.newKeySet();
    private static final Object flushLock = new Object();
    private static volatile ScheduledFuture<?> flushTickFuture;

    private static ExecutorService saveExecutor;
    private static final Object executorLock = new Object();

    private static ExecutorService getSaveExecutor() {
        ExecutorService ex = saveExecutor;
        if (ex == null) {
            synchronized (executorLock) {
                ex = saveExecutor;
                if (ex == null) {
                    saveExecutor = ex = createSaveExecutor();
                }
            }
        }
        return ex;
    }

    private static ExecutorService createSaveExecutor() {
        if (USE_VIRTUAL_THREAD) {
            return Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual()
                            .name("Linear IO Save - ", 0)
                            .factory()
            );
        }
        final int maxThreads = Math.max(1, Math.min(SAVE_THREAD_MAX_COUNT, 32));
        final ThreadFactory factory = r -> {
            final Thread t = new Thread(r, "Linear IO Save");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 3);
            return t;
        };
        return new ThreadPoolExecutor(
                maxThreads, maxThreads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                factory
        );
    }

    private static void ensureFlushTick() {
        if (flushTickFuture != null) return;
        synchronized (executorLock) {
            if (flushTickFuture != null) return;
            final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "Linear Flush Scheduler");
                t.setDaemon(true);
                return t;
            });
            flushTickFuture = scheduler.scheduleWithFixedDelay(
                    LinearRegionFile::drainPendingFlushes,
                    SAVE_DELAY_MS, SAVE_DELAY_MS, TimeUnit.MILLISECONDS
            );
        }
    }

    private static void drainPendingFlushes() {
        final List<LinearRegionFile> batch;
        synchronized (flushLock) {
            if (pendingFlush.isEmpty()) return;
            batch = new ArrayList<>(pendingFlush);
            pendingFlush.clear();
        }
        final ExecutorService exec = getSaveExecutor();
        for (final LinearRegionFile file : batch) {
            exec.execute(() -> {
                boolean flushFailed = false;
                try {
                    file.flush();
                } catch (IOException e) {
                    flushFailed = true;
                    LOGGER.error("Region file {} flush failed", file.regionFile, e);
                } finally {
                    synchronized (flushLock) {
                        file.flushQueued = false;
                        if (!flushFailed && file.hasPendingSave()) {
                            file.flushQueued = true;
                            pendingFlush.add(file);
                        }
                    }
                }
            });
        }
    }

    private void requestFlush() {
        if (flushQueued) return;
        synchronized (flushLock) {
            if (flushQueued) return;
            flushQueued = true;
            pendingFlush.add(this);
        }
        ensureFlushTick();
    }

    public synchronized boolean doesChunkExist(ChunkPos pos) throws Exception {
        openRegionFile();
        openBucket(pos.x(), pos.z());
        return this.bufferUncompressedSize[getChunkIndex(pos.x(), pos.z())] > 0;
    }

    public synchronized void flush() throws IOException {
        openRegionFile();
        if (!isMarkedToSave()) return;

        final Path tempPath = this.regionFile.resolveSibling(this.regionFile.getFileName() + ".tmp");

        try {
            writeToTempFile(tempPath);
        } catch (IOException | RuntimeException e) {
            markToSave();
            Files.deleteIfExists(tempPath);
            throw new IOException("Region file flush failed for " + this.regionFile, e);
        }

        try {
            Files.move(tempPath, this.regionFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tempPath, this.regionFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                markToSave();
                e.addSuppressed(e2);
                Files.deleteIfExists(tempPath);
                throw new IOException("Region file move failed for " + this.regionFile, e);
            }
        }
    }

    private void writeToTempFile(Path tempPath) throws IOException {
        try (FileChannel fileChannel = FileChannel.open(tempPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             DataOutputStream dataStream = new DataOutputStream(java.nio.channels.Channels.newOutputStream(fileChannel))) {

            dataStream.writeLong(SUPERBLOCK);
            dataStream.writeByte(VERSION);
            dataStream.writeLong(getTimestamp());
            dataStream.writeByte(gridSize);

            String fileName = regionFile.getFileName().toString();
            String[] parts = fileName.split("\\.");
            int regionX = 0;
            int regionZ = 0;
            try {
                if (parts.length >= 4) {
                    regionX = Integer.parseInt(parts[1]);
                    regionZ = Integer.parseInt(parts[2]);
                } else {
                    LOGGER.warn("Unexpected file name format: " + fileName);
                }
            } catch (NumberFormatException e) {
                LOGGER.error("Failed to parse region coordinates from file name: " + fileName, e);
            }

            dataStream.writeInt(regionX);
            dataStream.writeInt(regionZ);

            boolean[] chunkExistenceBitmap = new boolean[1024];
            for (int i = 0; i < 1024; i++) {
                chunkExistenceBitmap[i] = (this.bufferUncompressedSize[i] > 0);
            }
            writeSerializedExistenceBitmap(dataStream, chunkExistenceBitmap);

            writeNBTFeatures(dataStream);

            int bucketMisses = 0;
            byte[][] buckets = new byte[gridSize * gridSize][];
            for (int bx = 0; bx < gridSize; bx++) {
                for (int bz = 0; bz < gridSize; bz++) {
                    if (bucketBuffers != null && bucketBuffers[bx * gridSize + bz] != null) {
                        buckets[bx * gridSize + bz] = bucketBuffers[bx * gridSize + bz];
                        continue;
                    }
                    bucketMisses++;

                    ByteArrayOutputStream bucketStream = new ByteArrayOutputStream();
                    ZstdOutputStream zstdStream = new ZstdOutputStream(bucketStream, this.compressionLevel);
                    DataOutputStream bucketDataStream = new DataOutputStream(zstdStream);

                    boolean hasData = false;
                    for (int cx = 0; cx < 32 / gridSize; cx++) {
                        for (int cz = 0; cz < 32 / gridSize; cz++) {
                            int chunkIndex = (bx * 32 / gridSize + cx) + (bz * 32 / gridSize + cz) * 32;
                            if (this.bufferUncompressedSize[chunkIndex] > 0) {
                                hasData = true;
                                byte[] chunkData = new byte[this.bufferUncompressedSize[chunkIndex]];
                                this.decompressor.decompress(this.buffer[chunkIndex], 0, chunkData, 0, this.bufferUncompressedSize[chunkIndex]);
                                bucketDataStream.writeInt(chunkData.length + 8);
                                bucketDataStream.writeLong(this.chunkTimestamps[chunkIndex]);
                                bucketDataStream.write(chunkData);
                            } else {
                                bucketDataStream.writeInt(0);
                                bucketDataStream.writeLong(this.chunkTimestamps[chunkIndex]);
                            }
                        }
                    }
                    bucketDataStream.close();

                    if (hasData) {
                        buckets[bx * gridSize + bz] = bucketStream.toByteArray();
                    }
                }
            }

            for (int i = 0; i < gridSize * gridSize; i++) {
                dataStream.writeInt(buckets[i] != null ? buckets[i].length : 0);
                dataStream.writeByte(this.compressionLevel);
                long rawHash = 0;
                if (buckets[i] != null) {
                    rawHash = LongHashFunction.xx().hashBytes(buckets[i]);
                }
                dataStream.writeLong(rawHash);
            }

            for (int i = 0; i < gridSize * gridSize; i++) {
                if (buckets[i] != null) {
                    dataStream.write(buckets[i]);
                }
            }

            dataStream.writeLong(SUPERBLOCK);
            dataStream.flush();
            fileChannel.force(true);
        }
    }

    private void writeNBTFeatures(DataOutputStream dataStream) throws IOException {
        dataStream.writeByte(0);
    }

    private void writeNBTFeature(DataOutputStream dataStream, String featureName, int featureValue) throws IOException {
        byte[] featureNameBytes = featureName.getBytes();
        dataStream.writeByte(featureNameBytes.length);
        dataStream.write(featureNameBytes);
        dataStream.writeInt(featureValue);
    }

    public static final int MAX_CHUNK_SIZE = 500 * 1024 * 1024;

    public synchronized void write(ChunkPos pos, ByteBuffer buffer) throws IOException {
        if (close) {
            throw new IOException("Write after close at " + pos + " in " + this.regionFile);
        }
        openRegionFile();
        openBucket(pos.x(), pos.z());
        try {
            byte[] b;
            if (buffer.hasArray()) {
                b = new byte[buffer.remaining()];
                System.arraycopy(buffer.array(), buffer.arrayOffset() + buffer.position(), b, 0, buffer.remaining());
            } else {
                b = new byte[buffer.remaining()];
                buffer.duplicate().get(b);
            }
            int uncompressedSize = b.length;

            if (uncompressedSize > MAX_CHUNK_SIZE) {
                throw new IOException("Chunk exceeds max size (" + uncompressedSize + " > " + MAX_CHUNK_SIZE + ") at " + pos + " in " + this.regionFile + "; existing chunk preserved");
            }

            int maxCompressedLength = this.compressor.maxCompressedLength(b.length);
            byte[] compressed = new byte[maxCompressedLength];
            int compressedLength = this.compressor.compress(b, 0, b.length, compressed, 0, maxCompressedLength);
            b = new byte[compressedLength];
            System.arraycopy(compressed, 0, b, 0, compressedLength);

            int index = getChunkIndex(pos.x(), pos.z());
            this.buffer[index] = b;
            this.chunkTimestamps[index] = getTimestamp();
            this.bufferUncompressedSize[index] = uncompressedSize;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Chunk write failed at " + pos + " in " + this.regionFile + "; existing chunk preserved", e);
        }
        markToSave();
        requestFlush();
    }

    public DataOutputStream getChunkDataOutputStream(ChunkPos pos) throws IOException {
        openRegionFile();
        openBucket(pos.x(), pos.z());
        return new DataOutputStream(new BufferedOutputStream(new LinearRegionFile.ChunkBuffer(pos)));
    }

    @Override
    public MoonriseRegionFileIO.RegionDataController.WriteData moonrise$startWrite(CompoundTag data, ChunkPos pos) throws IOException {
        final ChunkBuffer chunkBuffer = new ChunkBuffer(pos, false);
        final DataOutputStream out = new DataOutputStream(chunkBuffer);

        return new MoonriseRegionFileIO.RegionDataController.WriteData(
                data, MoonriseRegionFileIO.RegionDataController.WriteData.WriteResult.WRITE,
                out, regionFile -> regionFile.write(pos, ByteBuffer.wrap(chunkBuffer.toByteArray()))
        );
    }

    private class ChunkBuffer extends ByteArrayOutputStream {

        private final ChunkPos pos;
        private final boolean writeOnClose;

        public ChunkBuffer(ChunkPos chunkcoordintpair) {
            this(chunkcoordintpair, true);
        }

        private ChunkBuffer(ChunkPos chunkcoordintpair, boolean writeOnClose) {
            super();
            this.pos = chunkcoordintpair;
            this.writeOnClose = writeOnClose;
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (this.writeOnClose) {
                ByteBuffer bytebuffer = ByteBuffer.wrap(this.buf, 0, this.count);
                LinearRegionFile.this.write(this.pos, bytebuffer);
            }
        }
    }

    @Nullable
    public synchronized DataInputStream getChunkDataInputStream(ChunkPos pos) throws IOException {
        openRegionFile();
        openBucket(pos.x(), pos.z());

        if (this.bufferUncompressedSize[getChunkIndex(pos.x(), pos.z())] != 0) {
            byte[] content = new byte[bufferUncompressedSize[getChunkIndex(pos.x(), pos.z())]];
            try {
                this.decompressor.decompress(this.buffer[getChunkIndex(pos.x(), pos.z())], 0, content, 0, bufferUncompressedSize[getChunkIndex(pos.x(), pos.z())]);
            } catch (RuntimeException e) {
                throw new IOException("Corrupt in-memory chunk at " + pos + " in " + this.regionFile, e);
            }
            return new DataInputStream(new ByteArrayInputStream(content));
        }
        return null;
    }

    public synchronized void clear(ChunkPos pos) throws IOException {
        if (close) return;
        openRegionFile();
        openBucket(pos.x(), pos.z());
        int i = getChunkIndex(pos.x(), pos.z());
        this.buffer[i] = null;
        this.bufferUncompressedSize[i] = 0;
        this.chunkTimestamps[i] = 0;
        markToSave();
        requestFlush();
    }

    public synchronized boolean hasChunk(ChunkPos pos) {
        try {
            openRegionFile();
            openBucket(pos.x(), pos.z());
            return this.bufferUncompressedSize[getChunkIndex(pos.x(), pos.z())] > 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void close() throws IOException {
        openRegionFile();
        if (close) return;
        close = true;

        synchronized (markedToSaveLock) {
            markedToSave = true;
        }

        try {
            flush();
        } catch (IOException e) {
            throw new IOException("Region flush IOException " + e + " " + this.regionFile);
        }
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    private static int getTimestamp() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    public boolean recalculateHeader() {
        return false;
    }

    public void setOversized(int x, int z, boolean something) {
    }

    public CompoundTag getOversizedData(int x, int z) throws IOException {
        return null;
    }

    public boolean isOversized(int x, int z) {
        return false;
    }

    public Path getPath() {
        return this.regionFile;
    }

    private boolean[] deserializeExistenceBitmap(ByteBuffer buffer) {
        boolean[] result = new boolean[1024];
        for (int i = 0; i < 128; i++) {
            byte b = buffer.get();
            for (int j = 0; j < 8; j++) {
                result[i * 8 + j] = ((b >> (7 - j)) & 1) == 1;
            }
        }
        return result;
    }

    private void writeSerializedExistenceBitmap(DataOutputStream out, boolean[] bitmap) throws IOException {
        for (int i = 0; i < 128; i++) {
            byte b = 0;
            for (int j = 0; j < 8; j++) {
                if (bitmap[i * 8 + j]) {
                    b |= (1 << (7 - j));
                }
            }
            out.writeByte(b);
        }
    }
}
