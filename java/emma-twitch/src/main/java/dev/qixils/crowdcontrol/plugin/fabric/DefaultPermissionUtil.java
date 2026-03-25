package dev.qixils.crowdcontrol.plugin.fabric;

import dev.qixils.crowdcontrol.common.util.PermissionWrapper;
import dev.qixils.crowdcontrol.plugin.fabric.utils.PermissionUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.permissions.PermissionSetSupplier;
import net.minecraft.world.entity.Entity;

/**
 * Default permission util that checks vanilla permission system.
 * Replaces the removed FabricPermissionUtil.
 */
public class DefaultPermissionUtil extends PermissionUtil {
	@Override
	public boolean check(Entity entity, PermissionWrapper permission) {
		if (permission.getDefaultPermission() == PermissionWrapper.DefaultPermission.ALL)
			return true;
		if (entity instanceof ServerPlayer player) {
			return player.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
		}
		return false;
	}

	@Override
	public boolean check(PermissionSetSupplier entity, PermissionWrapper permission) {
		if (permission.getDefaultPermission() == PermissionWrapper.DefaultPermission.ALL)
			return true;
		return entity.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
	}
}
