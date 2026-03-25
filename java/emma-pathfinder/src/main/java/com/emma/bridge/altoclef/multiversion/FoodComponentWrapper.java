package adris.altoclef.multiversion;

import net.minecraft.component.type.FoodComponent;


public class FoodComponentWrapper {


    public static FoodComponentWrapper of(FoodComponent component) {
        if (component == null) return null;

        return new FoodComponentWrapper(component);
    }

    private final FoodComponent component;

    private FoodComponentWrapper(FoodComponent component) {
        this.component = component;
    }

    public int getHunger() {
        return component.nutrition();
    }

    public float getSaturationModifier() {
        return component.saturation();
    }
}
