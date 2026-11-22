package org.agentos.assistant;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/** Native adapters for the official Taku mobile design-system roles. */
public final class ChatDesign {
    public enum Role {
        /** Current conversation name: Hanken Medium 18/27.9. */
        CONTEXT(R.style.TextAppearance_Taku_Context,
                R.style.TextAppearance_Taku_Context_Zh, 1),
        /** Principal result title: Space 24/25.2; Chinese 24/31.2. */
        HEADING(R.style.TextAppearance_Taku_Heading,
                R.style.TextAppearance_Taku_Heading_Zh, 2),
        /** Message content and input: Hanken Regular 16/24. */
        BODY(R.style.TextAppearance_Taku_Body,
                R.style.TextAppearance_Taku_Body_Zh, 0),
        /** Short emphasized labels: Hanken Medium 16/24. */
        LABEL(R.style.TextAppearance_Taku_Label,
                R.style.TextAppearance_Taku_Label_Zh, 1),
        /** Process and previews: Hanken Regular 14/19.88. */
        META(R.style.TextAppearance_Taku_Meta,
                R.style.TextAppearance_Taku_Meta_Zh, 0),
        /** Status labels: Hanken Medium 14/19.88. */
        META_MEDIUM(R.style.TextAppearance_Taku_MetaMedium,
                R.style.TextAppearance_Taku_MetaMedium_Zh, 1),
        /** Source and time: Hanken Regular 12/15.6. */
        CAPTION(R.style.TextAppearance_Taku_Caption,
                R.style.TextAppearance_Taku_Caption_Zh, 0);

        final int latinStyle;
        final int chineseStyle;
        final int fontSlot;

        Role(int latinStyle, int chineseStyle, int fontSlot) {
            this.latinStyle = latinStyle;
            this.chineseStyle = chineseStyle;
            this.fontSlot = fontSlot;
        }
    }

    private static final Map<Resources, Typeface[]> FONTS = new WeakHashMap<>();

    private ChatDesign() {}

    /**
     * Call after setText so mixed-language content gets the appropriate type role.
     * The caller's color is retained: this helper does not impose a light palette
     * on an existing dark surface, nor change the view's padding or layout.
     */
    public static void text(TextView view, Role role) {
        TakuText.applyStyle(view, role.latinStyle, role.chineseStyle);
        view.setTypeface(typeface(view.getContext(), role.fontSlot));
    }

    /** Resolve an explicit semantic color resource for the caller's surface. */
    public static int color(Context context, int colorResource) {
        return context.getResources().getColor(colorResource, context.getTheme());
    }

    /** Resolve a named dimension, including the user's font scaling for sp values. */
    public static int pixels(Context context, int dimensionResource) {
        return context.getResources().getDimensionPixelSize(dimensionResource);
    }

    /** A fresh tinted UI icon. Brand/app assets and the assistant mark (ic_tako_mark) must stay untinted. */
    public static Drawable icon(Context context, int drawableResource, int tintColor) {
        Drawable drawable = context.getDrawable(drawableResource);
        if (drawable == null) return null;
        drawable = drawable.mutate();
        drawable.setTint(tintColor);
        return drawable;
    }

    private static Typeface typeface(Context context, int slot) {
        synchronized (FONTS) {
            Resources resources = context.getResources();
            Typeface[] fonts = FONTS.get(resources);
            if (fonts == null) {
                fonts = new Typeface[3];
                FONTS.put(resources, fonts);
            }
            if (fonts[slot] == null) {
                fonts[slot] = slot == 2 ? TakuText.display(context)
                        : TakuText.body(context, slot == 1);
            }
            return fonts[slot];
        }
    }
}
