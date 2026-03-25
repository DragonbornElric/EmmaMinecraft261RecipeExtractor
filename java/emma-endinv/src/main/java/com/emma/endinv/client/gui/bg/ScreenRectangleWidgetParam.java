package com.emma.endinv.client.gui.bg;

public record ScreenRectangleWidgetParam(int x, int y, int width, int height) implements IRectangleParam {

    @Override
    public boolean hasClickedOn(double mouseX, double mouseY){
        return mouseX>= x && mouseX<= x + width && mouseY>= y && mouseY<= y + height;
    }
}
