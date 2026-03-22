/*
 * This file is part of Emmatone.
 *
 * Emmatone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Emmatone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Emmatone.  If not, see <https://www.gnu.org/licenses/>.
 */

package emmatone.utils;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;

public final class FireworkHelper {

    private FireworkHelper() {}

    public static LivingEntity getBoostedEntity(FireworkRocketEntity firework) {
        if (firework.isAttachedToEntity() && firework.attachedToEntity == null) {
            Entity entity = firework.level().getEntity(
                    firework.getEntityData().get(FireworkRocketEntity.DATA_ATTACHED_TO_TARGET).getAsInt());
            if (entity instanceof LivingEntity living) {
                firework.attachedToEntity = living;
            }
        }
        return firework.attachedToEntity;
    }
}
