package com.waenhancer.xposed.features.customization;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
    private static final WeakHashMap<View, Integer> originalBottomPaddings = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> fabListeners = new WeakHashMap<>();
    private static final WeakHashMap<View, FrameLayout> glassHosts = new WeakHashMap<>();
    private static final WeakHashMap<View, BlurView> glassBlurViews = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> styledIndicators = new WeakHashMap<>();
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
        glassEnabled = prefs.getBoolean("floating_bottom_bar_glass", true);
        glassOpacity = getPrefFloat(prefs, "floating_bottom_bar_glass_opacity", 35f);
        glassFillColor = getPrefColor(prefs, "floating_bottom_bar_fill_color", 0);

        // Hook the tab frame container
        Class<?> loadTabFrameClass = Unobfuscator.loadTabFrameClass(classLoader);
        XposedBridge.hookAllMethods(loadTabFrameClass, "setVisibility", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    View view = (View) param.thisObject;
                    if (view == null) return;
                    int visibility = (int) param.args[0];
                    View animTarget = getBarAnimationTarget(view);
                    if (animTarget != view && animTarget.getVisibility() != visibility) {
                        animTarget.setVisibility(visibility);
                    }
                } catch (Throwable ignored) {}
            }
        });

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
                        if (glassFillColor != 0) {
                            bgColor = glassFillColor;
                        } else if (prefs.getBoolean("changecolor", false)) {
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

                        if (view instanceof ViewGroup) {
                            ((ViewGroup) view).setClipChildren(false);
                            ((ViewGroup) view).setClipToPadding(false);
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                            view.setClipToOutline(false);
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

                        // Reduce the height of the menu view container to 50dp dynamically
                        final ViewGroup menuView = findMenuView(view);
                        if (menuView != null) {
                            menuView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                                private boolean isUpdating = false;
                                @Override
                                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                                    if (isUpdating) return;
                                    isUpdating = true;
                                    try {
                                        ViewGroup.LayoutParams lp = v.getLayoutParams();
                                        int targetHeight = (int) (50 * density);
                                        if (lp != null && lp.height != targetHeight) {
                                            lp.height = targetHeight;
                                            v.setLayoutParams(lp);
                                        }
                                    } finally {
                                        isUpdating = false;
                                    }
                                }
                            });
                            ViewGroup.LayoutParams menuLp = menuView.getLayoutParams();
                            if (menuLp != null) {
                                menuLp.height = (int) (50 * density);
                                menuView.setLayoutParams(menuLp);
                            }
                            // Allow children of menu view to draw outside bounds (overflow)
                            menuView.setClipChildren(false);
                            menuView.setClipToPadding(false);
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                menuView.setClipToOutline(false);
                            }
                        }

                        // Clear any default minimum height that forces the bottom bar to be tall
                        view.setMinimumHeight(0);

                        // Replace green chip active indicator with circular glow spotlight
                        replaceActiveIndicatorWithGlow(view, density);

                        // Adjust padding to center items within floating pill
                        int paddingVertical = (int) (3 * density);
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
                                    if (v.getMinimumHeight() != 0) {
                                        v.setMinimumHeight(0);
                                    }
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
                        XposedBridge.log(t);
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
                    rv.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        private boolean hasSetPadding = false;
                        @Override
                        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
                            try {
                                if (hasSetPadding) return;
                                if (v.getHeight() > 0 && v.getWidth() > 0) {
                                    if (isMainTabScrollable(v)) {
                                        View bottomNav = findBottomNavForScrollable(v);
                                        if (bottomNav != null) {
                                            float density = v.getContext().getResources().getDisplayMetrics().density;
                                            int paddingBottom = dp(density, SCROLL_BOTTOM_PADDING_DP);
                                            prepareScrollableBottomPadding(v, paddingBottom);
                                            hasSetPadding = true;
                                        }
                                    } else {
                                        restoreOriginalBottomPadding(v);
                                        hasSetPadding = true;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            });

            // Hook dispatchOnScrolled candidate methods in RecyclerView
            java.util.List<java.lang.reflect.Method> candidateMethods = new java.util.ArrayList<>();
            for (java.lang.reflect.Method m : recyclerViewClass.getDeclaredMethods()) {
                Class<?>[] paramTypes = m.getParameterTypes();
                if (paramTypes.length == 2 && paramTypes[0] == int.class && paramTypes[1] == int.class && m.getReturnType() == void.class) {
                    String name = m.getName();
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    if (name.equals("scrollBy") || name.equals("scrollTo") || name.equals("onMeasure") || name.equals("onSizeChanged") || name.equals("onLayout") || name.equals("setMeasuredDimension")) {
                        continue;
                    }
                    candidateMethods.add(m);
                }
            }

            for (java.lang.reflect.Method m : candidateMethods) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.args == null || param.args.length < 2) return;
                        View rv = (View) param.thisObject;
                        int dy = (int) param.args[1];
                        handleRecyclerViewScrolled(rv, dy);
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
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
            XposedBridge.log(t);
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

                    final View animTarget = getBarAnimationTarget(bottomNav);
                    final ViewGroup finalParentGroup = parentGroup;
                    bottomNav.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                int targetVisibility = View.GONE;
                                if (bottomNav.getVisibility() == View.VISIBLE && finalParentGroup.isShown()) {
                                    targetVisibility = View.VISIBLE;
                                    View convHost = findConversationViewHost(bottomNav);
                                    if (convHost != null && convHost.isShown()) {
                                        targetVisibility = View.GONE;
                                    }
                                }
                                if (animTarget.getVisibility() != targetVisibility) {
                                    animTarget.setVisibility(targetVisibility);
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            });

            // 4. Update inner scroll paddings and add scroll-to-hide listeners
            viewPager.post(() -> {
                try {
                    int paddingBottom = dp(density, SCROLL_BOTTOM_PADDING_DP);
                    setBottomPaddingAndScrollListeners(viewPager, paddingBottom, bottomNav);
                } catch (Throwable t) {
                    XposedBridge.log(t);
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
            XposedBridge.log(t);
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
                if (isScrollableClass(child.getClass())) {
                    final View scrollView = child;
                    scrollView.post(() -> {
                        try {
                            if (!isMainTabScrollable(scrollView)) {
                                restoreOriginalBottomPadding(scrollView);
                                return;
                            }
                            prepareScrollableBottomPadding(scrollView, paddingBottom);

                            String scrollClassName = scrollView.getClass().getName();
                            if (scrollClassName.contains("ScrollView") && scrollView instanceof ViewGroup) {
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

    private static void prepareScrollableBottomPadding(View scrollView, int paddingBottom) {
        if (!originalBottomPaddings.containsKey(scrollView)) {
            originalBottomPaddings.put(scrollView, scrollView.getPaddingBottom());
        }
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
    }

    private static void restoreOriginalBottomPadding(View scrollView) {
        Integer originalBottom = originalBottomPaddings.get(scrollView);
        if (originalBottom == null) return;
        if (scrollView.getPaddingBottom() == originalBottom) return;
        scrollView.setPadding(
            scrollView.getPaddingLeft(),
            scrollView.getPaddingTop(),
            scrollView.getPaddingRight(),
            originalBottom
        );
    }

    private static void handleRecyclerViewScrolled(View rv, int dy) {
        try {
            if (Math.abs(dy) > 50000) return; // Ignore layout measureSpec triggers (e.g. 1073743008)
            if (Math.abs(dy) < 5) return;
            if (!rv.isShown()) return; // Ignore background scrolls
            if (!isMainTabScrollable(rv)) return;

            View bottomNav = findBottomNavForScrollable(rv);
            if (bottomNav == null) return;
            onViewScrolled(bottomNav, dy);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void onViewScrolled(View bottomNav, int dy) {
        if (bottomNav == null) return;
        
        View barTarget = getBarAnimationTarget(bottomNav);
        if (!barTarget.isShown()) return; // Do not animate if bottom bar is hidden on screen
        
        float density = bottomNav.getContext().getResources().getDisplayMetrics().density;
        
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
            XposedBridge.log(t);
        }
    }

    private static boolean isDescendantOfTabsPager(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            String name = parent.getClass().getName();
            if (name.contains("TabsPager") || name.toLowerCase().contains("pager") || name.equals("androidx.viewpager.widget.ViewPager")) {
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

    private static View findBottomNavForScrollable(View scrollable) {
        ViewGroup rootLayout = getRootLayout(scrollable);
        View bottomNav = findBottomNavInRoot(rootLayout);
        if (bottomNav != null) return bottomNav;

        View rootView = scrollable != null ? scrollable.getRootView() : null;
        if (rootView instanceof ViewGroup) {
            bottomNav = findBottomNavInRoot((ViewGroup) rootView);
            if (bottomNav != null) return bottomNav;
        }

        return null;
    }

    private static boolean isScrollableClass(Class<?> clazz) {
        while (clazz != null && clazz != Object.class) {
            String name = clazz.getName();
            if (name.equals("androidx.recyclerview.widget.RecyclerView") || 
                name.equals("android.widget.ScrollView") ||
                name.contains("RecyclerView") || 
                name.contains("ScrollView") ||
                name.equals("android.widget.AbsListView") ||
                name.contains("ListView")) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static boolean isInsideConversation(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof View) {
                View v = (View) parent;
                if (v.getId() != View.NO_ID) {
                    try {
                        String entryName = v.getResources().getResourceEntryName(v.getId());
                        if ("conversation_view_host".equals(entryName)) {
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (parent instanceof View) {
                parent = ((View) parent).getParent();
            } else {
                break;
            }
        }
        return false;
    }

    private static boolean isMainTabScrollable(View view) {
        if (view == null) return false;

        if (!isScrollableClass(view.getClass())) return false;
        if (isInsideConversation(view)) return false;

        boolean isDescendant = isDescendantOfTabsPager(view);
        boolean isLarge = isLargeVerticalScrollable(view);
        
        // If it's a descendant of TabsPager and it is large vertical scrollable, it is a main tab list!
        if (isDescendant && isLarge) {
            return true;
        }

        // Fallback checks
        try {
            if (view.getId() != View.NO_ID) {
                String entryName = view.getResources().getResourceEntryName(view.getId());
                if ("list".equalsIgnoreCase(entryName) && isLarge) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        String className = view.getClass().getName();
        if ((className.contains("WDSList") || className.contains("ObservableRecyclerView") || className.contains("CallsHistory")) && isLarge) {
            return true;
        }

        return false;
    }

    private static boolean isLargeVerticalScrollable(View view) {
        int height = view.getHeight();
        int width = view.getWidth();
        float density = view.getContext().getResources().getDisplayMetrics().density;

        // If view is already measured, use its measured size
        if (height > 0 && width > 0) {
            return height >= dp(density, 280) && width >= dp(density, 240);
        }

        // If not measured yet (e.g. during onAttachedToWindow), check layout parameters
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            boolean heightOk = (lp.height == ViewGroup.LayoutParams.MATCH_PARENT || 
                               lp.height >= dp(density, 280));
            boolean widthOk = (lp.width == ViewGroup.LayoutParams.MATCH_PARENT || 
                              lp.width >= dp(density, 240));
            return heightOk && widthOk;
        }

        return false;
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
                host.setClipToOutline(false);
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
            if (bottomNav instanceof ViewGroup) {
                ((ViewGroup) bottomNav).setClipChildren(false);
                ((ViewGroup) bottomNav).setClipToPadding(false);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bottomNav.setBackgroundTintList(null);
                bottomNav.setClipToOutline(false);
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
            XposedBridge.log(t);
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
            XposedBridge.log(t);
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
            if (bottomNav instanceof ViewGroup) {
                ((ViewGroup) bottomNav).setClipChildren(false);
                ((ViewGroup) bottomNav).setClipToPadding(false);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bottomNav.setBackgroundTintList(null);
                bottomNav.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                bottomNav.setClipToOutline(false);
            }
            applyPillShadow(bottomNav, density);

        } catch (Throwable e) {
            XposedBridge.log(e);
        }
    }

    private static void applyPillShadow(View view, float density) {
        view.setElevation(PILL_ELEVATION_DP * density);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            view.setTranslationZ(PILL_TRANSLATION_Z_DP * density);
        }
    }

    /**
     * Replaces the Material 3 green chip active indicator on each tab item
     * with a circular/capsule translucent glow/spotlight effect wrapping both the icon and label.
     */
    private void replaceActiveIndicatorWithGlow(View tabFrame, float density) {
        try {
            // Style current view hierarchy
            findAndStyleActiveIndicators(tabFrame, density);
            
            // Watch for dynamically added children anywhere in the tree
            if (tabFrame instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) tabFrame;
                group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                    @Override
                    public void onChildViewAdded(View parent, View child) {
                        findAndStyleActiveIndicators(child, density);
                        
                        // If the child is a ViewGroup, also watch its hierarchy changes
                        if (child instanceof ViewGroup) {
                            ((ViewGroup) child).setOnHierarchyChangeListener(this);
                        }
                    }

                    @Override
                    public void onChildViewRemoved(View parent, View child) {}
                });
                
                // Recursively set the listener on all existing nested view groups
                setupHierarchyListenersRecursive(group, density);
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private void setupHierarchyListenersRecursive(ViewGroup group, final float density) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup childGroup = (ViewGroup) child;
                childGroup.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                    @Override
                    public void onChildViewAdded(View parent, View childView) {
                        findAndStyleActiveIndicators(childView, density);
                        if (childView instanceof ViewGroup) {
                            ((ViewGroup) childView).setOnHierarchyChangeListener(this);
                        }
                    }

                    @Override
                    public void onChildViewRemoved(View parent, View childView) {}
                });
                setupHierarchyListenersRecursive(childGroup, density);
            }
        }
    }



    /**
     * Recursively traverses views to find active indicator views and style their tab item parents.
     */
    private void findAndStyleActiveIndicators(View view, float density) {
        if (view == null) return;
        
        if (isActiveIndicatorView(view)) {
            setupGlowOnTabItem(view, density);
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            
            // Check if this group matches the pattern of an icon container (ImageView + simple View)
            View indicator = findActiveIndicatorInGroup(group);
            if (indicator != null) {
                setupGlowOnTabItem(indicator, density);
                return;
            }
            
            for (int i = 0; i < group.getChildCount(); i++) {
                findAndStyleActiveIndicators(group.getChildAt(i), density);
            }
        }
    }

    private static boolean isActiveIndicatorView(View view) {
        if (view.getId() != View.NO_ID) {
            try {
                String entryName = view.getResources().getResourceEntryName(view.getId());
                if (entryName != null && entryName.contains("active_indicator")) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        String name = view.getClass().getName();
        return name.contains("ActiveIndicator") || name.endsWith("ActiveIndicatorView");
    }

    private static View findActiveIndicatorInGroup(ViewGroup group) {
        View candidateIndicator = null;
        boolean hasImageView = false;

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof android.widget.ImageView) {
                hasImageView = true;
            } else if (child != null && !(child instanceof android.widget.TextView) && !(child instanceof ViewGroup)) {
                candidateIndicator = child;
            }
        }

        if (hasImageView && candidateIndicator != null) {
            return candidateIndicator;
        }
        return null;
    }

    /**
     * Replaces the background of the tab item view with our custom StateListDrawable
     * and makes the native active indicator invisible.
     */
    private void setupGlowOnTabItem(View activeIndicator, float density) {
        try {
            // Hide the default active indicator view by making its background transparent
            activeIndicator.setBackground(new ColorDrawable(0));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activeIndicator.setBackgroundTintList(null);
            }

            // Get the tab item view (grandparent of activeIndicator)
            ViewParent parent = activeIndicator.getParent();
            if (parent == null) return;
            ViewParent grandparent = parent.getParent();
            if (grandparent instanceof ViewGroup) {
                ViewGroup tabItem = (ViewGroup) grandparent;
                
                // Prevent duplicate styling on this tab item
                if (styledIndicators.containsKey(tabItem)) {
                    return;
                }
                styledIndicators.put(tabItem, true);

                // Find icon container and labels group to decrease spacing
                View iconContainer = null;
                View labelsGroup = null;
                for (int i = 0; i < tabItem.getChildCount(); i++) {
                    View child = tabItem.getChildAt(i);
                    if (child.getId() != View.NO_ID) {
                        try {
                            String entryName = child.getResources().getResourceEntryName(child.getId());
                            if (entryName != null) {
                                if (entryName.contains("icon_container")) {
                                    iconContainer = child;
                                } else if (entryName.contains("labels_group")) {
                                    labelsGroup = child;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (iconContainer == null && child instanceof android.widget.FrameLayout) {
                        iconContainer = child;
                    }
                    if (labelsGroup == null && child.getClass().getName().contains("BaselineLayout")) {
                        labelsGroup = child;
                    }
                }

                final View finalIconContainer = iconContainer;
                final View finalLabelsGroup = labelsGroup;

                // Add layout change listener to enforce smaller height and compact spacing dynamically
                View.OnLayoutChangeListener layoutListener = new View.OnLayoutChangeListener() {
                    private boolean isUpdating = false;

                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                               int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        if (isUpdating) return;
                        isUpdating = true;
                        try {
                            // Enforce compact height of the tab item
                            ViewGroup.LayoutParams lp = v.getLayoutParams();
                            int targetHeight = (int) (50 * density);
                            if (lp != null && lp.height != targetHeight) {
                                lp.height = targetHeight;
                                v.setLayoutParams(lp);
                            }

                            // Center the icon container slightly higher
                            if (finalIconContainer != null) {
                                finalIconContainer.setTranslationY(-7.5f * density);
                            }

                            // Shift the labels group up slightly and remove bottom padding to minimize gap without overlap
                            if (finalLabelsGroup != null) {
                                finalLabelsGroup.setTranslationY(-1.5f * density);
                                int targetBottomPadding = 0;
                                if (finalLabelsGroup.getPaddingBottom() != targetBottomPadding) {
                                    finalLabelsGroup.setPadding(
                                        finalLabelsGroup.getPaddingLeft(),
                                        finalLabelsGroup.getPaddingTop(),
                                        finalLabelsGroup.getPaddingRight(),
                                        targetBottomPadding
                                    );
                                }
                                // Make text size a bit smaller
                                if (finalLabelsGroup instanceof ViewGroup) {
                                    ViewGroup labelVg = (ViewGroup) finalLabelsGroup;
                                    for (int j = 0; j < labelVg.getChildCount(); j++) {
                                        View child = labelVg.getChildAt(j);
                                        if (child instanceof android.widget.TextView) {
                                            android.widget.TextView tv = (android.widget.TextView) child;
                                            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.0f);
                                        }
                                    }
                                }
                            }
                        } finally {
                            isUpdating = false;
                        }
                    }
                };

                tabItem.addOnLayoutChangeListener(layoutListener);
                // Trigger immediately to force initial layout update
                layoutListener.onLayoutChange(tabItem, 0, 0, 0, 0, 0, 0, 0, 0);

                // Set the StateListDrawable background on the tab item view
                Drawable bg = createTabItemBackground(tabItem.getContext(), density);
                tabItem.setBackground(bg);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tabItem.setBackgroundTintList(null);
                }

                // Ensure the tab item view doesn't clip our background
                tabItem.setClipChildren(false);
                tabItem.setClipToPadding(false);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tabItem.setClipToOutline(false);
                }
                if (tabItem.getParent() instanceof ViewGroup) {
                    ViewGroup pg = (ViewGroup) tabItem.getParent();
                    pg.setClipChildren(false);
                    pg.setClipToPadding(false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        pg.setClipToOutline(false);
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static Drawable createTabItemBackground(android.content.Context ctx, float density) {
        StateListDrawable sld = new StateListDrawable();
        Drawable glow = new LiquidOvalDrawable(ctx, density);
        
        sld.addState(new int[]{android.R.attr.state_selected}, glow);
        sld.addState(new int[]{android.R.attr.state_checked}, glow);
        sld.addState(new int[]{}, new ColorDrawable(0x00000000));
        
        return sld;
    }

    /**
     * Custom drawable that paints a centered vertical-leaning glass oval/capsule with iridescent chromatic curves.
     */
    private static class LiquidOvalDrawable extends Drawable {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint topRainbow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bottomRainbow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean isNight;
        private final int accentColor;
        private final float density;

        public LiquidOvalDrawable(android.content.Context ctx, float density) {
            this.density = density;
            this.isNight = DesignUtils.isNightMode(ctx);
            this.accentColor = DesignUtils.getThemeAccentColor(ctx);
            
            fillPaint.setStyle(Paint.Style.FILL);
            
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1.0f * density);
            strokePaint.setColor(isNight ? 0x45FFFFFF : 0x25000000);
            
            glowPaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStrokeWidth(1.2f * density);
            
            topRainbow.setStyle(Paint.Style.STROKE);
            topRainbow.setStrokeWidth(0.8f * density);
            
            bottomRainbow.setStyle(Paint.Style.STROKE);
            bottomRainbow.setStrokeWidth(0.8f * density);

            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(isNight ? 0x66000000 : 0x2C000000);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;

            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY(); // Symmetrically centered relative to the tab item bounds

            // Vertical oval bounds that overflow the bottom bar (width increased to 78% as requested)
            float ovalWidth = bounds.width() * 0.78f;
            float ovalHeight = bounds.height() + 16 * density; // Symmetrical overflow of 8dp top/bottom

            float left = cx - ovalWidth / 2f;
            float right = cx + ovalWidth / 2f;
            float top = cy - ovalHeight / 2f;
            float bottom = cy + ovalHeight / 2f;

            RectF rectF = new RectF(left, top, right, bottom);
            float cornerRadius = ovalWidth / 2f; // Capsule / Oval shape

            // 0. Soft dark drop shadow behind the frosted fill to increase the "3D glass" depth
            canvas.drawRoundRect(new RectF(left, top + 1.5f * density, right, bottom + 1.5f * density), cornerRadius, cornerRadius, shadowPaint);

            // 1. Frosted Glass Fill Gradient
            int startColor = isNight ? 0x2DFFFFFF : 0x70FFFFFF;
            int midColor = isNight ? 0x15FFFFFF : 0x40FFFFFF;
            int endColor = isNight ? 0x22FFFFFF : 0x55FFFFFF;
            
            android.graphics.LinearGradient fillGradient = new android.graphics.LinearGradient(
                    cx, top, cx, bottom,
                    new int[]{startColor, midColor, endColor},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );
            fillPaint.setShader(fillGradient);
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fillPaint);

            // Save canvas and clip to capsule path to ensure highlights do not draw outside the shape
            canvas.save();
            android.graphics.Path clipPath = new android.graphics.Path();
            clipPath.addRoundRect(rectF, cornerRadius, cornerRadius, android.graphics.Path.Direction.CW);
            canvas.clipPath(clipPath);

            // 3. Specular highlight glow at top edge (drawn inside the clipped area)
            android.graphics.LinearGradient glowGradient = new android.graphics.LinearGradient(
                    left, top, right, top + 10 * density,
                    new int[]{0x00FFFFFF, isNight ? 0x77FFFFFF : 0x55FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );
            glowPaint.setShader(glowGradient);
            canvas.drawArc(new RectF(left + 1f, top + 1f, right - 1f, top + 15 * density), 200, 140, false, glowPaint);

            // 4. Glassy Pixel Inner Edge Highlights (Clipped to draw purely inside the shape)
            // Top Curve: Thin, sharp 1px glass highlight
            android.graphics.LinearGradient topGrad = new android.graphics.LinearGradient(
                    left + 8 * density, top, right - 8 * density, top,
                    new int[]{0x00FFFFFF, 0xB8FFFFFF, 0xEEFFFFFF, 0xB8FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
                    Shader.TileMode.CLAMP
            );
            topRainbow.setShader(topGrad);
            canvas.drawArc(new RectF(left, top, right, top + 16 * density), 210, 120, false, topRainbow);

            // Bottom Curve: Thin, sharp 1px glass highlight mirroring bottom edge
            android.graphics.LinearGradient bottomGrad = new android.graphics.LinearGradient(
                    left + 8 * density, bottom, right - 8 * density, bottom,
                    new int[]{0x00FFFFFF, 0x68FFFFFF, 0x9EFFFFFF, 0x68FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.25f, 0.5f, 0.75f, 1f},
                    Shader.TileMode.CLAMP
            );
            bottomRainbow.setShader(bottomGrad);
            canvas.drawArc(new RectF(left, bottom - 16 * density, right, bottom), 30, 120, false, bottomRainbow);

            // Restore canvas clip state
            canvas.restore();

            // 2. White Translucent Border (drawn on top of the shape so it is crisp and unclipped)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, strokePaint);
        }

        @Override
        public void setAlpha(int alpha) {
            fillPaint.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
            glowPaint.setAlpha(alpha);
            topRainbow.setAlpha(alpha);
            bottomRainbow.setAlpha(alpha);
            shadowPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            // Ignore system/library tints to keep true glass colors
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Finds the NavigationBarMenuView within the tab frame hierarchy.
     */
    private static View findNavigationBarMenuView(View view) {
        if (view == null) return null;
        String name = view.getClass().getName();
        if (name.contains("NavigationBarMenuView") || name.contains("BottomNavigationMenuView")) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findNavigationBarMenuView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Recursively searches for a field by name up the class hierarchy.
     */
    private static java.lang.reflect.Field findFieldRecursive(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * Recursively searches for a method by name and parameter types up the class hierarchy.
     */
    private static java.lang.reflect.Method findMethodRecursive(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ignored) {}
            current = current.getSuperclass();
        }
        return null;
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


    private static View findConversationViewHost(View bottomNav) {
        try {
            ViewGroup root = getRootLayout(bottomNav);
            if (root != null) {
                int resId = bottomNav.getContext().getResources().getIdentifier("conversation_view_host", "id", bottomNav.getContext().getPackageName());
                if (resId != 0 && resId != View.NO_ID) {
                    return root.findViewById(resId);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static ViewGroup findMenuView(View tabFrame) {
        if (!(tabFrame instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) tabFrame;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                ViewGroup childGroup = (ViewGroup) child;
                if (childGroup.getChildCount() > 0) {
                    return childGroup;
                }
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Floating Bottom Bar";
    }
}
