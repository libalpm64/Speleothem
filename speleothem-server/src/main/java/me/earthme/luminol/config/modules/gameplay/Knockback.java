package me.earthme.luminol.config.modules.gameplay;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.FUNCTION, name = "knockback")
public class Knockback implements IConfigModule {
    @ConfigInfo(name = "flush_knockback", comments = """
            Flush the connection channel after knockback so the client
            immediately receives the entity's new position, preventing
            rubber-banding during player-versus-player combat.""")
    public static boolean flushKnockback = false;
}
