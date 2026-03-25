package com.emma.endinv.network.payloads;

import com.emma.endinv.menu.page.PageType;
import com.emma.endinv.network.payloads.toServer.ItemPageContext;
import com.emma.endinv.util.SortType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;

/**Both stored and synced data of player and page, obtained in specific Payloads.
 * See {@link ItemPageContext},{@link SyncedConfig}
 */
public record PageData(String pageRegKey, int rows, int columns, SortType sortType, boolean reverseSort, String search) {
    
    public static final PageData DEFAULT = new PageData(PageType.DEFAULT_KEY, 6, 9, SortType.DEFAULT, false, "");
    public static final Codec<PageData> CODEC = RecordCodecBuilder.create(
            instance -> instance.group(
                    Codec.STRING.optionalFieldOf("pageId", PageType.DEFAULT_KEY).forGetter(PageData::pageRegKey),
                    Codec.INT.optionalFieldOf("rows", DEFAULT.rows()).forGetter(PageData::rows),
                    Codec.INT.optionalFieldOf("columns", DEFAULT.columns()).forGetter(PageData::columns),
                    SortType.CODEC.optionalFieldOf("sortType",SortType.DEFAULT).forGetter(PageData::sortType),
                    Codec.BOOL.optionalFieldOf("reverseSort",false).forGetter(PageData::reverseSort),
                    Codec.STRING.optionalFieldOf("searching","").forGetter(PageData::search)
            ).apply(instance, PageData::new)
    );

    public PageData(int rows, int columns){
        this(PageType.DEFAULT_KEY,rows,columns,SortType.DEFAULT,false,"");
    }

    public static void encode(FriendlyByteBuf o,PageData data){
        o.writeUtf(data.pageRegKey);
        o.writeInt(data.rows);
        o.writeInt(data.columns);
        o.writeEnum(data.sortType);
        o.writeBoolean(data.reverseSort);
        o.writeUtf(data.search);
    }

    public static PageData decode(FriendlyByteBuf o){
        return new PageData(
                o.readUtf(),
                o.readInt(),
                o.readInt(),
                o.readEnum(SortType.class),
                o.readBoolean(),
                o.readUtf()
        );
    }

    public PageData copy(){
        return new PageData(this.pageRegKey,this.rows,this.columns,this.sortType,this.reverseSort,this.search);
    }
    public PageData ofRowChanged(int rows){
        return new PageData(this.pageRegKey,rows,this.columns,this.sortType,this.reverseSort,this.search);
    }
    public PageData ofColumnChanged(int columns){
        return new PageData(this.pageRegKey,this.rows,columns,this.sortType,this.reverseSort,this.search);
    }
    public PageData sortTypeChanged(SortType sortType){
        return new PageData(this.pageRegKey,this.rows,this.columns,sortType,this.reverseSort,this.search);
    }
    public PageData searchingChanged(String searching){
        return new PageData(this.pageRegKey,this.rows,this.columns,this.sortType,this.reverseSort,searching);
    }
    public PageData ofReverseSort(){
        return new PageData(this.pageRegKey,this.rows,this.columns,this.sortType,!this.reverseSort,this.search);
    }
    public PageData ofPageKeyChanged(String pageRegKey) {
        return new PageData(pageRegKey,this.rows,this.columns,this.sortType,!this.reverseSort,this.search);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PageData pageData
                && pageData.pageRegKey.equals(pageRegKey)
                && pageData.rows == rows && pageData.columns() ==columns
                && pageData.reverseSort == reverseSort && sortType ==pageData.sortType
                && Objects.equals(pageData.search,search);
    }
}
