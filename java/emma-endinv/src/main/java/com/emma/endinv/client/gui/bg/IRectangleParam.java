package com.emma.endinv.client.gui.bg;

import com.emma.endinv.options.config.ConfigEntryImpl;

import java.util.Arrays;
import java.util.List;

public interface IRectangleParam {
    int x();

    int y();

    int width();

    int height();

    default boolean hasClickedOn(double mouseX, double mouseY){
        return mouseX >= x() && mouseX <= x() + width()
                && mouseY >= y() && mouseY <= y() + height();
    }

    default List<Integer> toList(){
        return List.of(x(),y(),width(),height());
    }

    default IRectangleParam applyOffset(int basicX, int basicY){
        return new ScreenRectangleWidgetParam(x()+basicX,y()+basicY,width(),height());
    }

    static ConfigEntryImpl.ListEntry<Integer> createRectangleConfigEntry(String key, String[] comments, IRectangleParam defaultValue){
        return new ConfigEntryImpl.ListEntry<Integer>(
                key,
                arrayAppend(comments,PARAM_EXPLAIN),
                toList(defaultValue),
                ()->0,
                n -> n instanceof Integer
        ).setRange(4,4);
    }

    private static String[] arrayAppend(String[] array, String[] app){
        var ret = Arrays.copyOf(array,array.length+app.length);
        System.arraycopy(app,0,ret,array.length,app.length);
        return ret;
    }

    String[] PARAM_EXPLAIN = new String[]{
            "Takes 4 integers in index 0,1,2,3",
            "corresponding to x,y,width,height of rectangle"
    };

    static List<Integer> toList(IRectangleParam param){
        return List.of(
                param.x(),
                param.y(),
                param.width(),
                param.height()
        );
    }

    static IRectangleParam fromConfigEntry(ConfigEntryImpl.ListEntry<Integer> entry){
        List<Integer> list = entry.get();
        if(list.size()<4) throw new IllegalArgumentException("Invalid rectangle config, which provides a list with less than 4 size.");
        return new ScreenRectangleWidgetParam(list.get(0),list.get(1),list.get(2),list.get(3));
    }
}
