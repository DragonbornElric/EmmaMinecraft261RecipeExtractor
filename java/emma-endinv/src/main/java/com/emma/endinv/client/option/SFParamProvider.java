package com.emma.endinv.client.option;

import com.emma.endinv.client.gui.bg.IRectangleParam;
import com.emma.endinv.menu.page.PageType;

import java.util.List;

public interface SFParamProvider {

    int rows();
    int columns();
    int leftPos();
    int topPos();

    TextureMode textureMode();

    List<PageType> pages();
    int pageTabCount();
    IRectangleParam pageParamAdjusted();

    IRectangleParam pageTabParamAdjusted();
    IRectangleParam pageTabDecA();
    IRectangleParam pageTabIncA();

    IRectangleParam sortBoxA();
    IRectangleParam configButtonA();
    IRectangleParam reverseSortButtonA();
    IRectangleParam searchBoxA();
}
