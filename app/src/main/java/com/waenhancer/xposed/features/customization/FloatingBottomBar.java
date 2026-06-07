package com.waenhancer.xposed.features.customization;

import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.DesignUtils;
import com.waenhancer.xposed.utils.Utils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.WeakHashMap;

import eightbitlab.com.blurview.BlurAlgorithm;
import eightbitlab.com.blurview.BlurView;
import eightbitlab.com.blurview.RenderEffectBlur;
import eightbitlab.com.blurview.RenderScriptBlur;

public class FloatingBottomBar extends Feature {

    private static final int PILL_SIDE_MARGIN_DP = 16;
    private static final int PILL_BOTTOM_MARGIN_DP = 16;
    private static final int SCROLL_BOTTOM_PADDING_DP = 120;
    private static final int FAB_VISIBLE_OFFSET_DP = 80;
    private static final float PILL_ELEVATION_DP = 12f;
    private static final float PILL_TRANSLATION_Z_DP = 8f;
    private static final WeakHashMap<View, Boolean> styledBottomBars = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> registeredScrollListeners = new WeakHashMap<>();
    private static final WeakHashMap<View, Float> targetTranslations = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> fabListeners = new WeakHashMap<>();
    private static final WeakHashMap<View, FrameLayout> glassHosts = new WeakHashMap<>();
    private static final WeakHashMap<View, BlurView> glassBlurViews = new WeakHashMap<>();
    private static boolean scrollHideEnabled = true;
    private static boolean glassEnabled = false;
    private static float glassOpacity = 35f;
    private static int glassFillColor = 0;

    public FloatingBottomBar(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("floating_bottom_bar", false)) return;

        scrollHideEnabled = prefs.getBoolean("floating_bottom_bar_scroll_hide", true);
        glassEnabled = prefs.getBoolean("floating_bottom_bar_glass", false);
        glassOpacity = getPrefFloat(prefs, "floating_bottom_bar_glass_opacity", 35f);
        glassFillColor = getPrefColor(prefs, "floating_bottom_bar_fill_color", 0);
        XposedBridge.log("[WAEX] FloatingBottomBar.doHook() initialized");

        // Hook the tab frame container
        Class<?> loadTabFrameClass = Unobfuscator.loadTabFrameClass(classLoader);
        XposedBridge.hookAllMethods(loadTabFrameClass, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final View view = (View) param.thisObject;
                if (view == null) return;

                view.post(() -> {
                    try {
                        // Prevent duplicate initializations
                        if (styledBottomBars.containsKey(view)) return;
                        styledBottomBars.put(view, true);

                        float density = view.getContext().getResources().getDisplayMetrics().density;

                        // Create rounded shape background matching the theme surface/card color
                        GradientDrawable shape = new GradientDrawable();
                        shape.setShape(GradientDrawable.RECTANGLE);
                        shape.setCornerRadius(28 * density);

                        boolean isNight = DesignUtils.isNightMode(view.getContext());
                        int bgColor = isNight ? 0xff1f2c34 : 0xffffffff;
                        if (prefs.getBoolean("changecolor", false)) {
                            int customBg = DesignUtils.getPrimarySurfaceColor();
                            if (customBg != 0 && customBg != -1) {
                                bgColor = customBg;
                            }
                        }
                        
                        shape.setColor(bgColor);
                        // Subtle premium stroke matching host app's design
                        shape.setStroke(Math.max(1, (int) (0.6f * density)), isNight ? 0x18FFFFFF : 0x22000000);

                        // Prevent system tints from overriding our custom background shape
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            view.setBackgroundTintList(null);
                        }

                        if (glassEnabled) {
                            applyGlassmorphism(view, density);
                        } else {
                            view.setBackground(shape);
                            applyPillShadow(view, density);
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                view.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                            }
                        }

                        // Clear backgrounds of immediate children to prevent solid white rectangular overlays
                        makeChildrenTransparent(view);

                        // Adjust padding to center items within floating pill
                        int paddingVertical = (int) (6 * density);
                        view.setPadding(view.getPaddingLeft(), paddingVertical, view.getPaddingRight(), paddingVertical);

                        // Attach LayoutChangeListener to enforce margins, overriding parent-forced layout passes
                        view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                            private boolean isUpdating = false;

                            @Override
                            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                                if (isUpdating) return;
                                isUpdating = true;
                                try {
                                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                                        int marginSide = dp(density, PILL_SIDE_MARGIN_DP);
                                        int marginBottom = dp(density, PILL_BOTTOM_MARGIN_DP);
                                        if (glassHosts.containsKey(v)) return;
                                        if (mlp.leftMargin != marginSide || mlp.rightMargin != marginSide || mlp.bottomMargin != marginBottom) {
                                            mlp.leftMargin = marginSide;
                                            mlp.rightMargin = marginSide;
                                            mlp.bottomMargin = marginBottom;
                                            v.setLayoutParams(mlp);
                                        }
                                    }
                                } finally {
                                    isUpdating = false;
                                }
                            }
                        });

                        // Initial layout params update to force immediate layout
                        ViewGroup.LayoutParams lp = view.getLayoutParams();
                        if (lp instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                            int marginSide = dp(density, PILL_SIDE_MARGIN_DP);
                            int marginBottom = dp(density, PILL_BOTTOM_MARGIN_DP);
                            mlp.leftMargin = marginSide;
                            mlp.rightMargin = marginSide;
                            mlp.bottomMargin = marginBottom;
                            view.setLayoutParams(mlp);
                        }

                        // Make layouts overlap and hide adjacent divider lines
                        adjustLayoutOverlap(view, density);

                    } catch (Throwable t) {
                        XposedBridge.log("[WAEX] FloatingBottomBar styling failed: " + t.getMessage());
                    }
                });
            }
        });

        // Hook RecyclerView's onAttachedToWindow to dynamically hook scroll events/padding on pages
        try {
            Class<?> recyclerViewClass = XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", classLoader);
            XposedBridge.hookAllMethods(recyclerViewClass, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final View rv = (View) param.thisObject;
                    if (rv == null) return;
                    rv.post(() -> {
                        try {
                            if (isDescendantOfTabsPager(rv)) {
                                ViewGroup rootLayout = getRootLayout(rv);
                                if (rootLayout == null) return;
                                View bottomNav = findBottomNavInRoot(rootLayout);
                                if (bottomNav == null) return;

                                float density = rv.getContext().getResources().getDisplayMetrics().density;
                                int paddingBottom = dp(density, SCROLL_BOTTOM_PADDING_DP);

                                rv.setPadding(
                                    rv.getPaddingLeft(),
                                    rv.getPaddingTop(),
                                    rv.getPaddingRight(),
                                    paddingBottom
                                );
                                if (rv instanceof ViewGroup) {
                                    ViewGroup vg = (ViewGroup) rv;
                                    vg.setClipToPadding(false);
                                }

                                if (rv instanceof androidx.recyclerview.widget.RecyclerView) {
                                    androidx.recyclerview.widget.RecyclerView recyclerView = (androidx.recyclerview.widget.RecyclerView) rv;
                                    if (!registeredScrollListeners.containsKey(recyclerView)) {
                                        recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                                            @Override
                                            public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView rView, int dx, int dy) {
                                                onViewScrolled(bottomNav, dy);
                                            }
                                        });
                                        registeredScrollListeners.put(recyclerView, true);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    });
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Failed to hook RecyclerView.onAttachedToWindow: " + t.getMessage());
        }

        // Hook WDSFab constructors to add attach state change and layout change listeners.
        // This guarantees shifting the FABs up by 80dp to float cleanly above the bottom bar,
        // and overrides CoordinatorLayout behaviors that try to reset the translation.
        try {
            Class<?> wdsFabClass = XposedHelpers.findClass("com.whatsapp.ui.wds.components.fab.WDSFab", classLoader);
            XposedBridge.hookAllConstructors(wdsFabClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final View fab = (View) param.thisObject;
                    if (fab == null) return;
                    fab.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View v) {
                            if (fabListeners.containsKey(v)) return;
                            fabListeners.put(v, true);
                            v.post(() -> {
                                try {
                                    float density = v.getContext().getResources().getDisplayMetrics().density;
                                    ViewGroup rootLayout = getRootLayout(v);
                                    v.setTranslationY(getFabVisibleTranslation(density));
                                    
                                    v.setElevation(20 * density);
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                        v.setTranslationZ(12 * density);
                                    }
                                    
                                    // Add layout listener to keep translation and elevation on layout passes
                                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                                        @Override
                                        public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
                                            try {
                                                float d = view.getContext().getResources().getDisplayMetrics().density;
                                                view.setTranslationY(getFabVisibleTranslation(d));
                                                
                                                view.setElevation(20 * d);
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                                    view.setTranslationZ(12 * d);
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    });
                                } catch (Throwable ignored) {}
                            });
                        }

                        @Override
                        public void onViewDetachedFromWindow(View v) {}
                    });
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Failed to hook WDSFab constructors: " + t.getMessage());
        }
    }

    private void makeChildrenTransparent(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                child.setBackground(null);
                child.setBackgroundColor(0);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    child.setBackgroundTintList(null);
                }
                
                // Clear background drawables recursively for intermediate layouts/wrappers
                String className = child.getClass().getName();
                if (child instanceof ViewGroup && (className.contains("Frame") || className.contains("Linear") || className.contains("Relative") || className.contains("Menu"))) {
                    makeChildrenTransparent(child);
                }
            }
        }
    }

    private void adjustLayoutOverlap(final View bottomNav, final float density) {
        try {
            final ViewParent parent = bottomNav.getParent();
            if (!(parent instanceof ViewGroup)) return;
            final ViewGroup parentGroup = (ViewGroup) parent;

            makeContainerTransparent(parentGroup);

            // Walk up the hierarchy to find the root layout container (preferring the nested FrameLayout)
            ViewGroup root = null;
            if (parentGroup != null) {
                ViewParent grandparent = parentGroup.getParent();
                if (grandparent instanceof ViewGroup) {
                    ViewGroup conversationListViewHost = (ViewGroup) grandparent;
                    View nestedContent = conversationListViewHost.findViewById(android.R.id.content);
                    if (nestedContent instanceof ViewGroup) {
                        root = (ViewGroup) nestedContent;
                        XposedBridge.log("[WAEX] Found nested content FrameLayout for bottom bar reparenting");
                    }
                }
            }
            if (root == null) {
                root = getRootLayout(bottomNav);
            }
            if (root == null) {
                root = parentGroup; // Fallback
            }
            final ViewGroup rootLayout = root;

            // Find the ViewPager or ViewPager2 sibling/ancestor in rootLayout
            final View viewPager = findViewPager(rootLayout);
            if (viewPager == null) {
                XposedBridge.log("[WAEX] ViewPager not found in root layout. Overlap aborted.");
                return;
            }

            // 1. Hide dividers in the original parent before we move bottomNav
            hideAdjacentDividers(bottomNav, parentGroup, density);

            // 2. Ensure viewPager bottom margin is 0
            viewPager.post(() -> {
                try {
                    ViewGroup.LayoutParams lp = viewPager.getLayoutParams();
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                        if (mlp.bottomMargin != 0) {
                            mlp.bottomMargin = 0;
                            viewPager.setLayoutParams(mlp);
                        }
                    }
                } catch (Throwable ignored) {}
            });

            // 3. Move bottomNav out of WhatsApp's full-width bottom_nav_container.
            // That container owns the solid rectangle; the pill itself must be the overlay.
            parentGroup.post(() -> {
                try {
                    ViewGroup targetRoot = findBottomOverlayRoot(bottomNav, parentGroup, rootLayout);
                    if (targetRoot == null) targetRoot = rootLayout;
                    if (targetRoot == null) return;
                    ViewParent currentParent = bottomNav.getParent();
                    if (currentParent instanceof ViewGroup) {
                        ((ViewGroup) currentParent).removeView(bottomNav);
                        if (currentParent != targetRoot) {
                            collapseOldBottomNavContainer((ViewGroup) currentParent);
                        }
                    }

                    int marginSide = dp(density, PILL_SIDE_MARGIN_DP);
                    int marginBottom = dp(density, PILL_BOTTOM_MARGIN_DP);
                        ViewGroup.LayoutParams newLp = null;

                        if (targetRoot instanceof android.widget.FrameLayout) {
                            android.widget.FrameLayout.LayoutParams flp = new android.widget.FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            );
                            flp.gravity = android.view.Gravity.BOTTOM;
                            newLp = flp;
                        } else if (targetRoot.getClass().getName().contains("CoordinatorLayout")) {
                            try {
                                Class<?> clLpClass = Class.forName("androidx.coordinatorlayout.widget.CoordinatorLayout$LayoutParams");
                                java.lang.reflect.Constructor<?> ctor = clLpClass.getConstructor(int.class, int.class);
                                Object clLp = ctor.newInstance(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                java.lang.reflect.Field gravityField = clLpClass.getField("gravity");
                                gravityField.set(clLp, android.view.Gravity.BOTTOM);
                                newLp = (ViewGroup.LayoutParams) clLp;
                            } catch (Throwable ignored) {}
                        } else if (targetRoot instanceof android.widget.RelativeLayout) {
                            android.widget.RelativeLayout.LayoutParams rlp = new android.widget.RelativeLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            );
                            rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
                            newLp = rlp;
                        } else if (targetRoot.getClass().getName().contains("ConstraintLayout")) {
                            try {
                                Class<?> clLpClass = Class.forName("androidx.constraintlayout.widget.ConstraintLayout$LayoutParams");
                                java.lang.reflect.Constructor<?> ctor = clLpClass.getConstructor(int.class, int.class);
                                Object clLp = ctor.newInstance(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                java.lang.reflect.Field bottomToBottomField = clLpClass.getField("bottomToBottom");
                                bottomToBottomField.set(clLp, 0); // 0 is PARENT_ID
                                java.lang.reflect.Field leftToLeftField = clLpClass.getField("leftToLeft");
                                leftToLeftField.set(clLp, 0);
                                java.lang.reflect.Field rightToRightField = clLpClass.getField("rightToRight");
                                rightToRightField.set(clLp, 0);
                                newLp = (ViewGroup.LayoutParams) clLp;
                            } catch (Throwable ignored) {}
                        }

                        if (newLp == null) {
                            newLp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        }

                        if (newLp instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) newLp;
                            mlp.leftMargin = marginSide;
                            mlp.rightMargin = marginSide;
                            mlp.bottomMargin = marginBottom;
                        }

                    View barOverlay;
                    if (glassEnabled) {
                        barOverlay = installGlassHost(targetRoot, rootLayout, bottomNav, newLp, density);
                    } else {
                        targetRoot.addView(bottomNav, newLp);
                        barOverlay = bottomNav;
                    }

                    targetRoot.setClipChildren(false);
                    targetRoot.setClipToPadding(false);
                    ViewParent gp = targetRoot.getParent();
                    if (gp instanceof ViewGroup) {
                        ((ViewGroup) gp).setClipChildren(false);
                        ((ViewGroup) gp).setClipToPadding(false);
                    }

                    barOverlay.bringToFront();
                    applyPillShadow(barOverlay, density);
                    bottomNav.bringToFront();
                } catch (Throwable t) {
                    XposedBridge.log("[WAEX] Dynamic re-parenting failed: " + t.getMessage());
                }
            });

            // 4. Update inner scroll paddings and add scroll-to-hide listeners
            viewPager.post(() -> {
                try {
                    int paddingBottom = dp(density, SCROLL_BOTTOM_PADDING_DP);
                    setBottomPaddingAndScrollListeners(viewPager, paddingBottom, bottomNav);
                } catch (Throwable t) {
                    XposedBridge.log("[WAEX] ViewPager scroll listener setup failed: " + t.getMessage());
                }
            });

            // 5. Adjust initial position of FABs to float above the pill
            rootLayout.postDelayed(() -> {
                try {
                    java.util.List<View> fabs = findFabs(rootLayout);
                    for (View fab : fabs) {
                        fab.setTranslationY(getFabVisibleTranslation(density));
                    }
                } catch (Throwable ignored) {}
            }, 500);

        } catch (Throwable t) {
            XposedBridge.log("[WAEX] adjustLayoutOverlap failed: " + t.getMessage());
        }
    }

    private View findViewPager(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            String name = child.getClass().getName();
            // Match custom TabsPager or traditional ViewPager
            if (name.toLowerCase().contains("pager") || name.equals("androidx.viewpager.widget.ViewPager")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View vp = findViewPager((ViewGroup) child);
                if (vp != null) return vp;
            }
        }
        return null;
    }

    private void setBottomPaddingAndScrollListeners(View view, int paddingBottom, final View bottomNav) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                String name = child.getClass().getName();
                if (child instanceof androidx.recyclerview.widget.RecyclerView || 
                    child instanceof android.widget.AbsListView || 
                    child instanceof android.widget.ScrollView ||
                    name.contains("RecyclerView") ||
                    name.contains("ScrollView")) {
                    
                    final View scrollView = child;
                    scrollView.post(() -> {
                        try {
                            scrollView.setPadding(
                                scrollView.getPaddingLeft(),
                                scrollView.getPaddingTop(),
                                scrollView.getPaddingRight(),
                                paddingBottom
                            );
                            if (scrollView instanceof ViewGroup) {
                                ViewGroup vg = (ViewGroup) scrollView;
                                vg.setClipToPadding(false);
                            }

                            if (scrollView instanceof androidx.recyclerview.widget.RecyclerView) {
                                androidx.recyclerview.widget.RecyclerView rv = (androidx.recyclerview.widget.RecyclerView) scrollView;
                                if (!registeredScrollListeners.containsKey(rv)) {
                                    rv.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                                        @Override
                                        public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                                            onViewScrolled(bottomNav, dy);
                                        }
                                    });
                                    registeredScrollListeners.put(rv, true);
                                }
                            } else if (scrollView instanceof ViewGroup) {
                                ViewGroup vg = (ViewGroup) scrollView;
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    if (!registeredScrollListeners.containsKey(vg)) {
                                        vg.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                                            onViewScrolled(bottomNav, scrollY - oldScrollY);
                                        });
                                        registeredScrollListeners.put(vg, true);
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    });
                } else if (child instanceof ViewGroup) {
                    setBottomPaddingAndScrollListeners(child, paddingBottom, bottomNav);
                }
            }
        }
    }

    private static void onViewScrolled(View bottomNav, int dy) {
        if (bottomNav == null) return;
        
        float density = bottomNav.getContext().getResources().getDisplayMetrics().density;
        View barTarget = getBarAnimationTarget(bottomNav);
        
        // Use cached preference to prevent disk I/O in hot scroll path
        if (!scrollHideEnabled) {
            Float lastTarget = targetTranslations.get(barTarget);
            if (lastTarget == null || lastTarget != 0f) {
                targetTranslations.put(barTarget, 0f);
                barTarget.animate().translationY(0).setDuration(200).start();
                animateFabs(bottomNav, false, density);
            }
            return;
        }

        if (Math.abs(dy) < 5) return; // Skip minor/jitter scroll actions

        float targetTranslationY;
        int height = barTarget.getHeight();
        if (height <= 0) {
            height = bottomNav.getHeight();
        }
        if (height <= 0) {
            height = (int) (80 * density);
        }

        if (dy > 0) {
            targetTranslationY = height + (24 * density);
        } else {
            targetTranslationY = 0;
        }

        Float lastTarget = targetTranslations.get(barTarget);
        if (lastTarget != null && lastTarget == targetTranslationY) {
            // Already animating to this target, avoid thrashing
            return;
        }
        targetTranslations.put(barTarget, targetTranslationY);

        barTarget.animate()
            .translationY(targetTranslationY)
            .setDuration(250)
            .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
            .start();
        // Animate FABs in sync
        animateFabs(bottomNav, dy > 0, density);
    }

    private static void animateFabs(View bottomNav, boolean hide, float density) {
        try {
            ViewGroup root = getRootLayout(bottomNav);
            if (root == null) return;
            
            java.util.List<View> fabs = findFabs(root);
            for (View fab : fabs) {
                float targetTranslationY = hide ? 0f : getFabVisibleTranslation(density);
                Float lastFabTarget = targetTranslations.get(fab);
                if (lastFabTarget != null && lastFabTarget == targetTranslationY) {
                    continue;
                }
                targetTranslations.put(fab, targetTranslationY);
                fab.animate()
                    .translationY(targetTranslationY)
                    .setDuration(250)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .start();
            }
        } catch (Throwable ignored) {}
    }

    private static java.util.List<View> findFabs(ViewGroup root) {
        java.util.List<View> fabs = new java.util.ArrayList<>();
        findFabsRecursive(root, fabs);
        return fabs;
    }

    private static void findFabsRecursive(View view, java.util.List<View> fabs) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                String name = child.getClass().getName();
                if (name.contains("WDSFab") || name.contains("FloatingActionButton")) {
                    fabs.add(child);
                } else if (child instanceof ViewGroup) {
                    findFabsRecursive(child, fabs);
                }
            }
        }
    }

    private void hideAdjacentDividers(View bottomNav, ViewGroup originalParent, float density) {
        try {
            // Hide dividers inside the tab frame container itself
            if (bottomNav instanceof ViewGroup) {
                ViewGroup container = (ViewGroup) bottomNav;
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    int height = child.getHeight() > 0 ? child.getHeight() : (child.getLayoutParams() != null ? child.getLayoutParams().height : 0);
                    if (height > 0 && height <= (int) (4 * density)) {
                        child.setVisibility(View.GONE);
                    }
                }
            }
            
            // Hide dividers inside the original parent layout
            if (originalParent != null) {
                originalParent.setBackground(null);
                originalParent.setBackgroundColor(0);
                
                for (int i = 0; i < originalParent.getChildCount(); i++) {
                    View child = originalParent.getChildAt(i);
                    if (child != bottomNav) {
                        int height = child.getHeight() > 0 ? child.getHeight() : (child.getLayoutParams() != null ? child.getLayoutParams().height : 0);
                        if (height > 0 && height <= (int) (4 * density)) {
                            child.setVisibility(View.GONE);
                        }
                    }
                }
                
                // Hide dividers inside the original grandparent layout
                ViewParent grandparent = originalParent.getParent();
                if (grandparent instanceof ViewGroup) {
                    ViewGroup grandGroup = (ViewGroup) grandparent;
                    for (int i = 0; i < grandGroup.getChildCount(); i++) {
                        View child = grandGroup.getChildAt(i);
                        if (child != originalParent) {
                            int height = child.getHeight() > 0 ? child.getHeight() : (child.getLayoutParams() != null ? child.getLayoutParams().height : 0);
                            if (height > 0 && height <= (int) (4 * density)) {
                                child.setVisibility(View.GONE);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Failed to hide dividers: " + t.getMessage());
        }
    }

    private static boolean isDescendantOfTabsPager(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent.getClass().getName().contains("TabsPager")) {
                return true;
            }
            if (parent instanceof View) {
                parent = ((View) parent).getParent();
            } else {
                break;
            }
        }
        return false;
    }

    private static ViewGroup getRootLayout(View view) {
        View current = view;
        ViewGroup root = null;
        while (current != null) {
            if (current instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) current;
                String name = vg.getClass().getName();
                if (name.contains("CoordinatorLayout") || 
                    name.contains("ConstraintLayout") || 
                    name.contains("RelativeLayout") ||
                    vg.getId() == android.R.id.content ||
                    name.endsWith("DecorView")) {
                    root = vg;
                    if (vg.getId() == android.R.id.content || name.endsWith("DecorView")) {
                        break;
                    }
                }
            }
            ViewParent next = current.getParent();
            if (next instanceof View) {
                current = (View) next;
            } else {
                break;
            }
        }
        return root;
    }

    private static View findBottomNavInRoot(ViewGroup root) {
        if (root == null) return null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (styledBottomBars.containsKey(child)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View nested = findBottomNavInRoot((ViewGroup) child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static int dp(float density, int value) {
        return (int) (value * density + 0.5f);
    }

    private static float getFabVisibleTranslation(float density) {
        return -dp(density, FAB_VISIBLE_OFFSET_DP);
    }

    private static View getBarAnimationTarget(View bottomNav) {
        FrameLayout host = glassHosts.get(bottomNav);
        return host != null ? host : bottomNav;
    }

    private static void makeContainerTransparent(ViewGroup group) {
        if (group == null) return;
        group.setBackground(null);
        group.setBackgroundColor(0);
        group.setClipChildren(false);
        group.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            group.setBackgroundTintList(null);
        }
    }

    private static void collapseOldBottomNavContainer(ViewGroup group) {
        makeContainerTransparent(group);
        group.setPadding(0, 0, 0, 0);
        group.setMinimumHeight(0);
        ViewGroup.LayoutParams lp = group.getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            group.setLayoutParams(lp);
        }
        group.setVisibility(View.GONE);
    }

    private static ViewGroup findBottomOverlayRoot(View bottomNav, ViewGroup parentGroup, ViewGroup fallbackRoot) {
        ViewParent parent = parentGroup.getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup directParent = (ViewGroup) parent;
            if (!(directParent instanceof android.widget.LinearLayout)) {
                return directParent;
            }
        }
        ViewGroup root = getRootLayout(bottomNav);
        return root != null ? root : fallbackRoot;
    }

    private View installGlassHost(ViewGroup targetRoot, ViewGroup blurRoot, View bottomNav,
                                  ViewGroup.LayoutParams hostLp, float density) {
        try {
            removeGlassHost(bottomNav);

            android.content.Context ctx = bottomNav.getContext();
            FrameLayout host = new FrameLayout(ctx);
            host.setClipChildren(false);
            host.setClipToPadding(false);
            host.setBackground(createGlassOutlineShape(ctx, density));
            applyPillShadow(host, density);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                host.setClipToOutline(true);
                host.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }

            BlurView blurView = new BlurView(ctx);
            blurView.setBackground(createGlassShape(ctx, density, true));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                blurView.setClipToOutline(true);
                blurView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }

            FrameLayout.LayoutParams blurLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            host.addView(blurView, blurLp);

            bottomNav.setBackground(createGlassShape(ctx, density, false));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bottomNav.setBackgroundTintList(null);
                bottomNav.setClipToOutline(true);
                bottomNav.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }

            FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM
            );
            glassHosts.put(bottomNav, host);
            glassBlurViews.put(bottomNav, blurView);
            host.addView(bottomNav, navLp);

            setupBlurView(blurView, blurRoot != null ? blurRoot : targetRoot, bottomNav);
            targetRoot.addView(host, hostLp);
            return host;
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Glass host install failed: " + t.getMessage());
            glassHosts.remove(bottomNav);
            glassBlurViews.remove(bottomNav);
            targetRoot.addView(bottomNav, hostLp);
            bottomNav.setBackground(createGlassShape(bottomNav.getContext(), density, true));
            return bottomNav;
        }
    }

    private void setupBlurView(BlurView blurView, ViewGroup blurRoot, View bottomNav) {
        try {
            android.content.Context ctx = bottomNav.getContext();
            BlurAlgorithm algorithm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new RenderEffectBlur()
                    : new RenderScriptBlur(ctx);
            android.graphics.drawable.Drawable windowBg = null;
            View rootView = bottomNav.getRootView();
            if (rootView != null) {
                windowBg = rootView.getBackground();
            }
            blurView.setupWith(blurRoot, algorithm)
                    .setFrameClearDrawable(windowBg)
                    .setBlurRadius(18f)
                    .setOverlayColor(getGlassOverlayColor(ctx));
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Blur setup failed: " + t.getMessage());
        }
    }

    private static void removeGlassHost(View bottomNav) {
        try {
            FrameLayout host = glassHosts.remove(bottomNav);
            glassBlurViews.remove(bottomNav);
            if (host != null) {
                ViewParent parent = host.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(host);
                }
                host.removeView(bottomNav);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Applies a glassmorphism effect to the floating bottom bar.
     *
     * Uses a translucent theme-aware fill. In WhatsApp's hooked hierarchy a separate blur layer
     * is too easy to desync during tab/page transitions, which causes transparent or resizing pills.
     */
    private void applyGlassmorphism(final View bottomNav, final float density) {
        try {
            final android.content.Context ctx = bottomNav.getContext();
            if (ctx == null) return;

            bottomNav.setBackground(createGlassShape(ctx, density, true));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bottomNav.setBackgroundTintList(null);
                bottomNav.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                bottomNav.setClipToOutline(true);
            }
            applyPillShadow(bottomNav, density);

        } catch (Throwable e) {
            XposedBridge.log("[WAEX] applyGlassmorphism outer failed: " + e.getMessage());
        }
    }

    private static void applyPillShadow(View view, float density) {
        view.setElevation(PILL_ELEVATION_DP * density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setTranslationZ(PILL_TRANSLATION_Z_DP * density);
        }
    }

    private static GradientDrawable createGlassShape(android.content.Context ctx, float density, boolean includeFill) {
        GradientDrawable glassShape = new GradientDrawable();
        glassShape.setShape(GradientDrawable.RECTANGLE);
        glassShape.setCornerRadius(28 * density);
        glassShape.setColor(includeFill ? getGlassOverlayColor(ctx) : 0x00000000);
        glassShape.setStroke(Math.max(1, (int) (0.6f * density)), getGlassStrokeColor(ctx));
        return glassShape;
    }

    private static GradientDrawable createGlassOutlineShape(android.content.Context ctx, float density) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(28 * density);
        shape.setColor(0x00000000);
        return shape;
    }

    private static int getGlassOverlayColor(android.content.Context ctx) {
        int alpha = Math.max(0, Math.min(255, Math.round((glassOpacity / 100f) * 255f)));
        int rgb = resolveGlassFillColor(ctx) & 0x00FFFFFF;
        return (alpha << 24) | rgb;
    }

    private static int resolveGlassFillColor(android.content.Context ctx) {
        if (glassFillColor != 0) {
            return glassFillColor;
        }
        return DesignUtils.isNightMode(ctx) ? 0xff1f2c34 : 0xffffffff;
    }

    private static int getGlassStrokeColor(android.content.Context ctx) {
        return DesignUtils.isNightMode(ctx) ? 0x22FFFFFF : 0x26000000;
    }

    private static float getPrefFloat(SharedPreferences prefs, String key, float defaultValue) {
        try {
            return prefs.getFloat(key, defaultValue);
        } catch (Throwable ignored) {
            try {
                return (float) prefs.getInt(key, (int) defaultValue);
            } catch (Throwable ignoredToo) {
                return defaultValue;
            }
        }
    }

    private static int getPrefColor(SharedPreferences prefs, String key, int defaultValue) {
        try {
            if (!prefs.contains(key)) return defaultValue;
            return prefs.getInt(key, defaultValue);
        } catch (Throwable ignored) {
            try {
                String value = prefs.getString(key, null);
                return value != null ? android.graphics.Color.parseColor(value) : defaultValue;
            } catch (Throwable ignoredToo) {
                return defaultValue;
            }
        }
    }


    @NonNull
    @Override
    public String getPluginName() {
        return "Floating Bottom Bar";
    }
}
