package com.waenhancer.xposed.features.customization;

import android.annotation.SuppressLint;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.MenuItem;
import android.widget.BaseAdapter;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.Feature;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.core.devkit.UnobfuscatorCache;
import com.waenhancer.xposed.utils.ReflectionUtils;
import com.waenhancer.xposed.utils.Utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.SharedPreferences;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SeparateGroup extends Feature {

    public static final int CHATS = 200;
    public static final int STATUS = 300;
    public static final int GROUPS = 500;

    public static ArrayList<Integer> tabs = new ArrayList<>();
    public static HashMap<Integer, Object> tabInstances = new HashMap<>();

    public SeparateGroup(@NonNull ClassLoader loader, @NonNull SharedPreferences preferences) {
        super(loader, preferences);
    }

    @Override
    public void doHook() throws Throwable {
        if (!prefs.getBoolean("separategroups", false)) return;

        Class<?> bottomNavigationViewCls = Unobfuscator.findFirstClassUsingName(
                classLoader,
                org.luckypray.dexkit.query.enums.StringMatchType.EndsWith,
                ".BottomNavigationView"
        );
        XposedHelpers.findAndHookMethod(
                bottomNavigationViewCls,
                "getMaxItemCount",
                XC_MethodReplacement.returnConstant(6)
        );

        // Modifying tab list order
        hookTabList();

        // Setting group icon
        hookTabIcon();

        // Setting up fragments
        hookTabInstance();

        // Setting group tab name
        hookTabName();

        // Setting tab count
        hookTabCount();
    }

    @NonNull
    @Override
    public String getPluginName() {
        return "Separate Group";
    }

    @SuppressLint("Range")
    private void hookTabCount() {
        try {
            Method runMethod = Unobfuscator.loadTabCountMethod(classLoader);
            logDebug(Unobfuscator.getMethodDescriptor(runMethod));

            Method enableCountMethod = Unobfuscator.loadEnableCountTabMethod(classLoader);
            Constructor<?> badgeWrapperConstructor = Unobfuscator.loadEnableCountTabBadgeWrapper(classLoader);
            Constructor<?> badgeItemConstructor = Unobfuscator.loadEnableCountTabBadgeItem(classLoader);
            Class<?> emptyBadgeClass = Unobfuscator.loadEnableCountTabEmptyBadgeClass(classLoader);
            logDebug(Unobfuscator.getMethodDescriptor(enableCountMethod));

            XposedBridge.hookMethod(enableCountMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int indexTab = (int) param.args[2];
                    if (indexTab == tabs.indexOf(CHATS)) {
                        param.setResult(null);

                        new Thread(() -> {
                            try {
                                int chatCount = 0;
                                int groupCount = 0;
                                SQLiteDatabase db = MessageStore.getInstance().getDatabase();
                                if (db == null) return;
                                String sql = "SELECT * FROM chat WHERE unseen_message_count != 0";
                                Cursor cursor = db.rawQuery(sql, null);
                                if (cursor != null) {
                                    try {
                                        while (cursor.moveToNext()) {
                                            int jid = cursor.getInt(cursor.getColumnIndex("jid_row_id"));
                                            int groupType = cursor.getInt(cursor.getColumnIndex("group_type"));
                                            int archived = cursor.getInt(cursor.getColumnIndex("archived"));
                                            int chatLocked = cursor.getInt(cursor.getColumnIndex("chat_lock"));
                                            if (archived != 0 || (groupType != 0 && groupType != 6) || chatLocked != 0) {
                                                continue;
                                            }
                                            String sql2 = "SELECT * FROM jid WHERE _id == ?";
                                            Cursor cursor1 = db.rawQuery(sql2, new String[]{String.valueOf(jid)});
                                            if (cursor1 != null) {
                                                try {
                                                    if (!cursor1.moveToFirst()) continue;
                                                    String server = cursor1.getString(cursor1.getColumnIndex("server"));
                                                    if ("g.us".equals(server)) {
                                                        groupCount++;
                                                    } else {
                                                        chatCount++;
                                                    }
                                                } finally {
                                                    cursor1.close();
                                                }
                                            }
                                        }
                                    } finally {
                                        cursor.close();
                                    }
                                }

                                if (tabs.contains(CHATS) && tabInstances.containsKey(CHATS)) {
                                    Object chatsBadge;
                                    if (chatCount <= 0) {
                                        chatsBadge = XposedHelpers.getStaticObjectField(emptyBadgeClass, "A00");
                                    } else {
                                        chatsBadge = badgeWrapperConstructor.newInstance(
                                                badgeItemConstructor.newInstance(chatCount)
                                        );
                                    }
                                    param.args[1] = chatsBadge;
                                }

                                if (tabs.contains(GROUPS) && tabInstances.containsKey(GROUPS)) {
                                    Object groupsBadge;
                                    if (groupCount <= 0) {
                                        groupsBadge = XposedHelpers.getStaticObjectField(emptyBadgeClass, "A00");
                                    } else {
                                        groupsBadge = badgeWrapperConstructor.newInstance(
                                                badgeItemConstructor.newInstance(groupCount)
                                        );
                                    }
                                    // Invoke on main thread
                                    final Object finalGroupsBadge = groupsBadge;
                                    final int groupsIndex = tabs.indexOf(GROUPS);
                                    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                                    handler.post(() -> {
                                        try {
                                            XposedBridge.invokeOriginalMethod(
                                                    param.method,
                                                    param.thisObject,
                                                    new Object[]{param.args[0], finalGroupsBadge, groupsIndex}
                                            );
                                        } catch (Throwable t) {
                                            XposedBridge.log("SeparateGroup: Error setting group badge: " + t);
                                        }
                                    });
                                }
                            } catch (Throwable t) {
                                XposedBridge.log("SeparateGroup: Error in tab count thread: " + t);
                            }
                        }).start();
                    }
                }
            });
        } catch (Throwable t) {
            logDebug("hookTabCount error", t);
        }
    }

    private void hookTabIcon() {
        try {
            Method iconTabMethod = Unobfuscator.loadIconTabMethod(classLoader);
            logDebug(Unobfuscator.getMethodDescriptor(iconTabMethod));
            Method menuAddAndroidX = Unobfuscator.loadAddMenuAndroidX(classLoader);
            logDebug(menuAddAndroidX.toString());

            XposedBridge.hookMethod(iconTabMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    XC_MethodHook.Unhook hooked = XposedBridge.hookMethod(menuAddAndroidX, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam innerParam) throws Throwable {
                            if (innerParam.args.length > 2 && (int) innerParam.args[1] == GROUPS) {
                                MenuItem menuItem = (MenuItem) innerParam.getResult();
                                menuItem.setIcon(
                                        Utils.getID("home_tab_communities_selector", "drawable")
                                );
                            }
                        }
                    });
                    param.setObjectExtra("hooked", hooked);
                }

                @SuppressLint("ResourceType")
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    XC_MethodHook.Unhook hooked = (XC_MethodHook.Unhook) param.getObjectExtra("hooked");
                    if (hooked != null) {
                        hooked.unhook();
                    }
                }
            });
        } catch (Throwable t) {
            logDebug("hookTabIcon error", t);
        }
    }

    @SuppressLint("ResourceType")
    private void hookTabName() {
        try {
            Method tabNameMethod = Unobfuscator.loadTabNameMethod(classLoader);
            XposedBridge.hookMethod(tabNameMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int tab = (int) param.args[0];
                    if (tab == GROUPS) {
                        param.setResult(UnobfuscatorCache.getInstance().getString("groups"));
                    }
                }
            });
        } catch (Throwable t) {
            logDebug("hookTabName error", t);
        }
    }

    private void hookTabInstance() {
        try {
            Class<?> cFragClass = Unobfuscator.findFirstClassUsingName(
                    classLoader,
                    org.luckypray.dexkit.query.enums.StringMatchType.EndsWith,
                    ".ConversationsFragment"
            );

            Method getTabMethod = Unobfuscator.loadGetTabMethod(classLoader);
            logDebug(Unobfuscator.getMethodDescriptor(getTabMethod));

            Method methodTabInstance = Unobfuscator.loadTabFragmentMethod(classLoader);
            logDebug(Unobfuscator.getMethodDescriptor(methodTabInstance));

            Constructor<?> recreateFragmentMethod = Unobfuscator.loadRecreateFragmentConstructor(classLoader);

            Pattern pattern = Pattern.compile("android:switcher:\\d+:(\\d+)");

            Class<?> fragmentClass = Unobfuscator.loadFragmentClass(classLoader);

            // Hook recreate fragment constructor
            XposedBridge.hookMethod(recreateFragmentMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    String string;
                    Object arg0 = param.args[0];
                    if (arg0 instanceof Bundle) {
                        @SuppressWarnings("deprecation")
                        Parcelable state = ((Bundle) arg0).getParcelable("state");
                        if (state == null) return;
                        string = state.toString();
                    } else {
                        string = param.args[2].toString();
                    }
                    Matcher matcher = pattern.matcher(string);
                    if (matcher.find()) {
                        String group1 = matcher.group(1);
                        if (group1 == null) return;
                        int tabId;
                        try {
                            tabId = Integer.parseInt(group1);
                        } catch (NumberFormatException e) {
                            return;
                        }
                        if (tabId == GROUPS || tabId == CHATS) {
                            Field fragmentField = ReflectionUtils.getFieldByType(
                                    param.thisObject.getClass(),
                                    fragmentClass
                            );
                            Object convFragment = ReflectionUtils.getObjectField(fragmentField, param.thisObject);
                            tabInstances.remove(tabId);
                            if (convFragment != null) {
                                tabInstances.put(tabId, convFragment);
                            }
                        }
                    }
                }
            });

            // Hook getTab method
            XposedBridge.hookMethod(getTabMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int index = (int) param.args[0];
                    if (index < 0 || index >= tabs.size()) return;
                    int tabId = tabs.get(index);
                    if (tabId == GROUPS || tabId == CHATS) {
                        java.lang.reflect.Constructor<?>[] constructors = cFragClass.getDeclaredConstructors();
                        for (java.lang.reflect.Constructor<?> ctor : constructors) {
                            if (ctor.getParameterCount() == 0) {
                                ctor.setAccessible(true);
                                Object convFragment = ctor.newInstance();
                                param.setResult(convFragment);
                                break;
                            }
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    int index = (int) param.args[0];
                    if (index < 0 || index >= tabs.size()) return;
                    int tabId = tabs.get(index);
                    tabInstances.remove(tabId);
                    if (param.getResult() != null) {
                        tabInstances.put(tabId, param.getResult());
                    }
                }
            });

            // Hook tab fragment method (filter chat list)
            XposedBridge.hookMethod(methodTabInstance, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    List<?> chatsList = (List<?>) param.getResult();
                    List<?> resultList = filterChat(param.thisObject, chatsList);
                    param.setResult(resultList);
                }
            });

            // Hook fab method
            Method fabintMethod = Unobfuscator.loadFabMethod(classLoader);
            logDebug(Unobfuscator.getMethodDescriptor(fabintMethod));

            XposedBridge.hookMethod(fabintMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (tabInstances.get(GROUPS) == param.thisObject) {
                        param.setResult(GROUPS);
                    }
                }
            });

            // Hook publishResults / getFilters method
            Method publishResultsMethod = Unobfuscator.loadGetFiltersMethod(classLoader);
            logDebug(Unobfuscator.getMethodDescriptor(publishResultsMethod));

            final Method finalPublishResultsMethod = publishResultsMethod;
            XposedBridge.hookMethod(publishResultsMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object filters = param.args[1];
                    List<?> chatsList = (List<?>) XposedHelpers.getObjectField(filters, "values");
                    Field baseField = ReflectionUtils.getFieldByExtendType(
                            finalPublishResultsMethod.getDeclaringClass(),
                            BaseAdapter.class
                    );
                    if (baseField == null) return;
                    Field convField = ReflectionUtils.getFieldByType(baseField.getType(), cFragClass);
                    Object thiz = convField.get(baseField.get(param.thisObject));
                    if (thiz == null) return;
                    List<?> resultList = filterChat(thiz, chatsList);
                    XposedHelpers.setObjectField(filters, "values", resultList);
                    XposedHelpers.setIntField(filters, "count", resultList.size());
                }
            });
        } catch (Throwable t) {
            logDebug("hookTabInstance error", t);
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> filterChat(Object thiz, List<?> chatsList) {
        Object tabChat = tabInstances.get(CHATS);
        Object tabGroup = tabInstances.get(GROUPS);

        if (tabChat != thiz && tabGroup != thiz) {
            return chatsList;
        }

        ArrayListFilter editableChatList = new ArrayListFilter(tabGroup == thiz);
        editableChatList.addAll((Collection<Object>) chatsList);
        return editableChatList;
    }

    private void hookTabList() {
        try {
            Method onCreateTabList = Unobfuscator.loadTabListMethod(classLoader);
            logDebug(Unobfuscator.getMethodDescriptor(onCreateTabList));

            XposedBridge.hookMethod(onCreateTabList, new XC_MethodHook() {
                @SuppressWarnings("unchecked")
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    ArrayList<Integer> resultTabs = (ArrayList<Integer>) param.getResult();
                    if (resultTabs == null) return;
                    tabs = resultTabs;
                    if (!tabs.contains(GROUPS)) {
                        tabs.add(tabs.isEmpty() ? 0 : 1, GROUPS);
                    }
                }
            });
        } catch (Throwable t) {
            logDebug("hookTabList error", t);
        }
    }

    /**
     * A custom ArrayList that filters chat items based on whether they belong to groups or contacts.
     * Used to separate the chat list into individual chats and group chats.
     */
    public static class ArrayListFilter extends ArrayList<Object> {

        private final boolean isGroup;

        public ArrayListFilter(boolean isGroup) {
            this.isGroup = isGroup;
        }

        @Override
        public void add(int index, Object element) {
            if (checkGroup(element)) {
                super.add(index, element);
            }
        }

        @Override
        public boolean add(Object element) {
            if (checkGroup(element)) {
                return super.add(element);
            }
            return true;
        }

        @Override
        public boolean addAll(@NonNull Collection<?> c) {
            for (Object chat : c) {
                if (checkGroup(chat)) {
                    super.add(chat);
                }
            }
            return true;
        }

        private boolean checkGroup(Object chat) {
            if (chat == null) return true;

            Object jid = null;
            try {
                jid = XposedHelpers.getObjectField(chat, "A00");
            } catch (Throwable ignored) {
            }
            if (jid == null) {
                try {
                    jid = XposedHelpers.getObjectField(chat, "A01");
                } catch (Throwable ignored) {
                }
            }
            if (jid == null) return true;

            if (XposedHelpers.findMethodExactIfExists(jid.getClass(), "getServer") != null) {
                String server = (String) XposedHelpers.callMethod(jid, "getServer");
                if (server == null) return true;
                if (isGroup) {
                    return "broadcast".equals(server) || "g.us".equals(server);
                } else {
                    return "s.whatsapp.net".equals(server) || "lid".equals(server);
                }
            }
            return true;
        }
    }
}
