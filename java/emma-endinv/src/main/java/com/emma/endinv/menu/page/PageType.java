package com.emma.endinv.menu.page;

import com.emma.endinv.util.ItemKey;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static net.minecraft.tags.ItemTags.*;

public class PageType {

    public static final String DEFAULT_KEY = "all_items";

    private static final boolean IS_CLIENT =
            FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;

    private static final List<Predicate<ItemKey>> equipmentSubclassifications = List.of(
            ofEquipmentSlotType(EquipmentSlot.HEAD),
            ofEquipmentSlotType(EquipmentSlot.CHEST),
            ofEquipmentSlotType(EquipmentSlot.LEGS),
            ofEquipmentSlotType(EquipmentSlot.FEET),
            ofEquipmentSlotType(EquipmentSlot.MAINHAND),
            ofEquipmentSlotType(EquipmentSlot.OFFHAND)
    );

    private static Predicate<ItemKey> ofEquipmentSlotType(EquipmentSlot slot){
        return it-> {
            var comp = it.toStack(1).getComponents().get(DataComponents.EQUIPPABLE);
            return comp!=null && comp.slot() == slot;
        };
    }

    public static final List<TagKey<Item>> WEAPON_TAGS = new ArrayList<>();
    public static final List<TagKey<Item>> TOOL_TAGS = new ArrayList<>();
    public static final List<TagKey<Item>> EQUIPPABLE_TAGS = new ArrayList<>();

    public static final PageType ALL_ITEMS = createClassifiedPage(DEFAULT_KEY,null,"chest");
    public static final PageType BLOCK_ITEMS = createClassifiedPage("block_items",(stack)->stack.getItem() instanceof BlockItem,"stone");
    public static final PageType WEAPONS = createClassifiedPage("weapons",PageType::isWeapon,"iron_sword");
    public static final PageType TOOLS = createClassifiedPage("tools",PageType::isTool,"iron_pickaxe");
    public static final PageType EQUIPMENTS = createServerSafe(
            "equipments",PageType::isDefenceEquipment,Identifier.withDefaultNamespace("iron_chestplate"),
            () -> (type,manager)-> new com.emma.endinv.client.gui.page.SegClassifyItemDisplay(
                    type, manager, equipmentSubclassifications, false, true)
    );
    public static final PageType CONSUMABLE = createClassifiedPage("consumable",PageType::isFoodOrPotion,"bread");
    //updatable entry: Let items who have::: .components().has(DataComponents.STORED_ENCHANTMENTS
    public static final PageType ENCHANTED_BOOKS = createServerSafe(
            "enchanted_books",
            stack -> stack.getItem() == Items.ENCHANTED_BOOK,
            Identifier.withDefaultNamespace("enchanted_book"),
            () -> (t, f) -> new com.emma.endinv.client.gui.page.ItemEntryDisplay(
                    t, f, com.emma.endinv.client.gui.page.ItemEntryDisplay.DescriptionProvider::fromEnch
            )
    );
    public static final PageType BOOKMARK = createServerSafe(
            "bookmark",null,Identifier.withDefaultNamespace("book"),
            () -> com.emma.endinv.client.gui.page.StarredItemPage::new
    );

    /**
     * Page constructor — nullable on dedicated server where GUI classes are absent.
     * Only ever called on client via {@link #buildPage}.
     */
    @Nullable
    private final PageConstructor constructor;
    @Nullable
    public final Predicate<ItemStack> itemClassify;
    @Nullable
    public Identifier icon = null;
    public final String registerName;

    @FunctionalInterface
    public interface PageConstructor {
        /**
         * DisplayPage's constructor or its variation.
         * Called by ScreenFramework's constructor on client only.
         */
        com.emma.endinv.client.gui.page.DisplayPage create(PageType pageType,
                com.emma.endinv.client.gui.ScreenFramework manager);
    }

    public PageType(@Nullable PageConstructor constructor, String registerName){
        this.constructor = constructor;
        this.registerName = registerName;
        this.itemClassify = null;
    }

    public PageType(@Nullable PageConstructor constructor, String registerName,@Nullable Predicate<ItemStack> itemClassify){
        this.constructor = constructor;
        this.itemClassify = itemClassify;
        this.registerName = registerName;
    }

    public PageType(@Nullable PageConstructor constructor, String registerName,@Nullable Predicate<ItemStack> itemClassify,@Nullable Identifier icon){
        this.constructor = constructor;
        this.itemClassify = itemClassify;
        this.icon = icon;
        this.registerName = registerName;
    }

    /**
     * Create a classified page that uses ItemDisplay on client.
     * On dedicated server, the constructor is null (never called).
     */
    public static PageType createClassifiedPage(String registerName,@Nullable Predicate<ItemStack> itemClassify, String icon){
        return createServerSafe(registerName, itemClassify, Identifier.withDefaultNamespace(icon),
                () -> com.emma.endinv.client.gui.page.ItemDisplay::new);
    }

    /**
     * Create a page type with a client-only constructor supplied lazily.
     * The supplier is only evaluated on client, avoiding class loading on server.
     */
    public static PageType createServerSafe(String registerName, @Nullable Predicate<ItemStack> itemClassify,
                                            @Nullable Identifier icon, Supplier<PageConstructor> clientCtor) {
        PageConstructor ctor = IS_CLIENT ? clientCtor.get() : null;
        return new PageType(ctor, registerName, itemClassify, icon);
    }

    /**
     * Build new DisplayPage.<p>
     * Called by ScreenFramework's constructor on client.
     */
    public com.emma.endinv.client.gui.page.DisplayPage buildPage(com.emma.endinv.client.gui.ScreenFramework meta){
        if (constructor == null) {
            throw new IllegalStateException("PageType.buildPage() called on dedicated server for: " + registerName);
        }
        var page = constructor.create(this, meta);
        if(icon!=null) page.icon = icon;
        return page;
    }

    public String toString(){
        return this.registerName;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PageType pageType
                && Objects.equals(pageType.itemClassify, itemClassify)
                && Objects.equals(pageType.registerName, registerName);
    }

    private static boolean isWeapon(ItemStack itemStack){
        Item item = itemStack.getItem();
        return
                item instanceof  AxeItem ||
                item instanceof  TridentItem ||
                item instanceof ProjectileWeaponItem ||
                WEAPON_TAGS.stream().anyMatch(itemStack::is);
    }

    private static boolean isTool(ItemStack itemStack){
        Item item = itemStack.getItem();
        return
                item instanceof AxeItem ||
                item instanceof ShearsItem ||
                item instanceof ShovelItem ||
                item instanceof FlintAndSteelItem ||
                item instanceof FishingRodItem ||
                TOOL_TAGS.stream().anyMatch(itemStack::is);
    }

    private static boolean isDefenceEquipment(ItemStack itemStack){
        Item item = itemStack.getItem();
        return
                itemStack.has(DataComponents.EQUIPPABLE) ||item instanceof ShieldItem ||
                item == Items.ELYTRA ||
                EQUIPPABLE_TAGS.stream().anyMatch(itemStack::is);
    }

    private static boolean isFoodOrPotion(ItemStack itemStack){
        Item item = itemStack.getItem();
        return item instanceof PotionItem || itemStack.has(DataComponents.FOOD);
    }

    static {
        WEAPON_TAGS.add(SWORDS);
        WEAPON_TAGS.add(AXES);
        TOOL_TAGS.add(AXES);
        TOOL_TAGS.add(PICKAXES);
        TOOL_TAGS.add(HOES);
        TOOL_TAGS.add(SHOVELS);
    }
}
