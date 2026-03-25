package adris.altoclef.mixins;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;

import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.events.PlayerDamageEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MC 1.21.8: damage(ServerWorld, DamageSource, float) is server-only.
 * clientDamage(DamageSource) on Entity is the client-side damage hook.
 * We target Entity so Loom can remap the method name, then filter to
 * only fire for the local player.
 */
@Mixin(Entity.class)
public class PlayerDamageMixin {

    @Inject(
        method = "clientDamage",
        at = @At("HEAD")
    )
    private void onClientDamage(DamageSource source, CallbackInfoReturnable<Boolean> ci) {
        if ((Object) this instanceof ClientPlayerEntity) {
            EventBus.publish(new PlayerDamageEvent(source, 0f));
        }
    }
}
