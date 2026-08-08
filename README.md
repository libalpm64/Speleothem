<p align="center">
  <img src="./Speleothem.png" alt="Speleothem" width="360">
</p>

# Speleothem

Speleothem is a high-performance, region-threaded Minecraft server fork for large survival and anarchy servers.

It targets Java 25 and Minecraft 26.2, and retains Folia's region-threading model. Speleothem adds production hardening, secure world generation, NBT safety fixes, entity corrections, and network safeguards maintained by libalpm64.

Speleothem was originally a fork of Luminol. Since Luminol no longer exists as an active project, Speleothem now continues independently with its own maintained patch set while preserving upstream attribution.

## Downloads

Release builds are available from [GitHub Releases](https://github.com/libalpm64/Speleothem/releases).

## Building

Speleothem requires JDK 25.

```bash
git clone https://github.com/libalpm64/Speleothem.git
cd Speleothem
./gradlew applyAllPatches
./gradlew :speleothem-server:createPaperclipJar
```

The runnable server jar is produced in `speleothem-server/build/libs`.

## API

Published development bundles and API artifacts use the Maven group `dev.libalpm64.speleothem`. The Java package namespace inherited from the upstream API remains stable for plugin compatibility.

## Patch attribution

Speleothem preserves the original patch authors and project markers from Folia, Paper, Luminol, and their upstream projects. Speleothem-maintained patches are marked `Speleothem` and authored by libalpm64.

## License

Speleothem inherits the licenses of its upstream projects. See [LICENSE.md](LICENSE.md) and the files under `licenses/` for details.
