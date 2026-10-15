package com.Evga314.farplanetwocontinuation.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Opens the Far Plane settings from Mod Menu. Only loaded when Mod Menu is installed. */
public class FarPlaneModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return LodConfigScreen::new;
    }
}
