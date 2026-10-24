plugins {
    id("dev.kikugie.stonecutter")
    // Uploads each version's jar to Modrinth (see publishMods in build.gradle.kts).
    id("me.modmuss50.mod-publish-plugin") version "2.2.1" apply false
}

// The version the sources in src/ are currently written for. Switch with the
// "Set active project to ..." Gradle tasks (or `gradlew "Set active project to 1.21.11"`).
stonecutter active "26.3"

// Renames between Minecraft versions, applied to the sources when switching versions
// (https://stonecutter.kikugie.dev/wiki/v2/reference/syntax/replacements). Each pair is
// "older name" -> "newer name"; changes that need different code use //? if comments instead.
stonecutter parameters {
    replacements {
        // 26.1 renamed GUI rendering from render/draw to "extract render state".
        string(current.parsed >= "26.1") {
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace("components.PlayerFaceRenderer;", "components.PlayerFaceExtractor;")
            replace("PlayerFaceRenderer.draw(", "PlayerFaceExtractor.extractRenderState(")
            replace(".drawString(font", ".text(font")
            replace(".renderOutline(", ".outline(")
            replace(".drawCenteredString(", ".centeredText(")
            replace("void render(GuiGraphics", "void extractRenderState(GuiGraphicsExtractor")
            replace("super.render(g", "super.extractRenderState(g")
            replace("void renderBackground(GuiGraphics", "void extractBackground(GuiGraphicsExtractor")
            replace("method = \"render\"", "method = \"extractRenderState\"")
            replace("method = \"renderPingIcon\"", "method = \"extractPingIcon\"")
            // Fabric API renamed key bindings to key mappings, like Mojang's names.
            replace("keybinding.v1.KeyBindingHelper", "keymapping.v1.KeyMappingHelper")
            replace("KeyBindingHelper.registerKeyBinding(", "KeyMappingHelper.registerKeyMapping(")
        }
        // 26.3 switched input to SDL, which has one keyboard key type instead of GLFW's two.
        string(current.parsed >= "26.3") {
            replace("Type.KEYSYM", "Type.KEYBOARD")
        }
    }
}
