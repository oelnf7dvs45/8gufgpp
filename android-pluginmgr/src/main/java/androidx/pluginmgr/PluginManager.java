/*
 * Copyright (C) 2015 HouKx <hkx.aidream@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.pluginmgr;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Looper;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import androidx.pluginmgr.delegate.DelegateActivityThread;
import androidx.pluginmgr.environment.CreateActivityData;
import androidx.pluginmgr.environment.PlugInfo;
import androidx.pluginmgr.environment.PluginClassLoader;
import androidx.pluginmgr.environment.PluginContext;
import androidx.pluginmgr.environment.PluginInstrumentation;
import androidx.pluginmgr.selector.DefaultActivitySelector;
import androidx.pluginmgr.selector.DynamicActivitySelector;
import androidx.pluginmgr.utils.FileUtil;
import androidx.pluginmgr.utils.PluginManifestUtil;
import androidx.pluginmgr.utils.Trace;
import androidx.pluginmgr.verify.PluginNotFoundException;
import androidx.pluginmgr.verify.PluginOverdueVerifier;
import androidx.pluginmgr.verify.SimpleLengthVerifier;

/**
 * ???????????????
 *
 * @author HouKangxi
 * @author Lody
 */
public class PluginManager implements FileFilter {

    /**
     * ?????????????????????
     */
    private static PluginManager SINGLETON;

    /**
     * ???????????? -- ???????????? ?????????
     */
    private final Map<String, PlugInfo> pluginPkgToInfoMap = new ConcurrentHashMap<String, PlugInfo>();

    /**
     * ???????????????
     */
    private Context context;
    /**
     * ??????dex opt????????????
     */
    private String dexOutputPath;
    /**
     * ????????????????????????????????????
     */
    private File dexInternalStoragePath;

    private ClassLoader pluginParentClassLoader = ClassLoader.getSystemClassLoader().getParent();

    /**
     * Activity?????????????????????
     */
    private PluginActivityLifeCycleCallback pluginActivityLifeCycleCallback;

    /**
     * ????????????????????? (???????????????????????????)
     */
    private PluginOverdueVerifier pluginOverdueVerifier = new SimpleLengthVerifier();

    private DynamicActivitySelector activitySelector = DefaultActivitySelector.getDefault();


    /**
     * ??????????????????????????????
     *
     * @param context Application?????????
     */
    private PluginManager(Context context) {
        if (!isMainThread()) {
            throw new IllegalThreadStateException("PluginManager must init in UI Thread!");
        }
        this.context = context;
        File optimizedDexPath = context.getDir(Globals.PRIVATE_PLUGIN_OUTPUT_DIR_NAME, Context.MODE_PRIVATE);
        dexOutputPath = optimizedDexPath.getAbsolutePath();
        dexInternalStoragePath = context.getDir(
                Globals.PRIVATE_PLUGIN_ODEX_OUTPUT_DIR_NAME, Context.MODE_PRIVATE
        );
        DelegateActivityThread delegateActivityThread = DelegateActivityThread.getSingleton();
        Instrumentation originInstrumentation = delegateActivityThread.getInstrumentation();
        if (!(originInstrumentation instanceof PluginInstrumentation)) {
            PluginInstrumentation pluginInstrumentation = new PluginInstrumentation(originInstrumentation);
            delegateActivityThread.setInstrumentation(pluginInstrumentation);
        }
    }


    /**
     * ???????????????????????????,<br>
     * NOTICE: ????????????????????????????????????????????????????????????!
     *
     * @return ?????????????????????
     */
    public static PluginManager getSingleton() {
        checkInit();
        return SINGLETON;
    }


    private static void checkInit() {
        if (SINGLETON == null) {
            throw new IllegalStateException("Please init the PluginManager first!");
        }
    }

    /**
     * ????????????????????????,????????????????????????Context,????????????????????????!
     *
     * @param context Application?????????
     */
    public static void init(Context context) {
        if (SINGLETON != null) {
            Trace.store("PluginManager have been initialized, YOU needn't initialize it again!");
            return;
        }
        Trace.store("init PluginManager...");
        SINGLETON = new PluginManager(context);
    }

    /**
     * ??????????????????ID?????????????????????????????????,????????????????????????,????????????.
     *
     * @param plugPkg ??????ID???????????????
     * @return ????????????
     */
    public PlugInfo tryGetPluginInfo(String plugPkg) throws PluginNotFoundException {
        PlugInfo plug = findPluginByPackageName(plugPkg);
        if (plug == null) {
            throw new PluginNotFoundException("plug not found by:"
                    + plugPkg);
        }
        return plug;
    }

    public File getPluginBasePath(PlugInfo plugInfo) {
        return new File(getDexInternalStoragePath(), plugInfo.getId() + "-dir");
    }

    public File getPluginLibPath(PlugInfo plugInfo) {
        return new File(getDexInternalStoragePath(), plugInfo.getId() + "-dir/lib/");
    }


    /**
     * ????????????????????????????????????
     *
     * @param packageName ????????????
     * @return ????????????
     */
    public PlugInfo findPluginByPackageName(String packageName) {
        return pluginPkgToInfoMap.get(packageName);
    }

    /**
     * ?????????????????????????????????
     *
     * @return ???????????????????????????
     */
    public Collection<PlugInfo> getPlugins() {
        return pluginPkgToInfoMap.values();
    }

    /**
     * ??????????????????????????????
     *
     * @param pkg ????????????
     */
    public void uninstallPluginByPkg(String pkg) {
        removePlugByPkg(pkg);
    }


    private PlugInfo removePlugByPkg(String pkg) {
        PlugInfo pl;
        synchronized (this) {
            pl = pluginPkgToInfoMap.remove(pkg);
            if (pl == null) {
                return null;
            }
        }
        return pl;
    }

    /**
     * ???????????????????????????????????????????????????
     * <p>
     * ????????????????????????Id
     *
     * @param pluginSrcDirFile - apk???apk??????
     * @return ????????????
     * @throws Exception
     */
    public Collection<PlugInfo> loadPlugin(final File pluginSrcDirFile)
            throws Exception {
        if (pluginSrcDirFile == null || !pluginSrcDirFile.exists()) {
            Trace.store("invalidate plugin file or Directory :"
                    + pluginSrcDirFile);
            return null;
        }
        if (pluginSrcDirFile.isFile()) {
            PlugInfo one = buildPlugInfo(pluginSrcDirFile, null, null);
            if (one != null) {
                savePluginToMap(one);
            }
            return Collections.singletonList(one);
        }
//        synchronized (this) {
//            pluginPkgToInfoMap.clear();
//        }
        File[] pluginApkFiles = pluginSrcDirFile.listFiles(this);
        if (pluginApkFiles == null || pluginApkFiles.length == 0) {
            throw new FileNotFoundException("could not find plugins in:"
                    + pluginSrcDirFile);
        }
        for (File pluginApk : pluginApkFiles) {
            try {
                PlugInfo plugInfo = buildPlugInfo(pluginApk, null, null);
                if (plugInfo != null) {
                    savePluginToMap(plugInfo);
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        return pluginPkgToInfoMap.values();
    }

    private synchronized void savePluginToMap(PlugInfo plugInfo) {
        pluginPkgToInfoMap.put(plugInfo.getPackageName(), plugInfo);
    }


    private PlugInfo buildPlugInfo(File pluginApk, String pluginId,
                                   String targetFileName) throws Exception {
        PlugInfo info = new PlugInfo();
        info.setId(pluginId == null ? pluginApk.getName() : pluginId);

        File privateFile = new File(dexInternalStoragePath,
                targetFileName == null ? pluginApk.getName() : targetFileName);

        info.setFilePath(privateFile.getAbsolutePath());
        //Copy Plugin to Private Dir
        if (!pluginApk.getAbsolutePath().equals(privateFile.getAbsolutePath())) {
            copyApkToPrivatePath(pluginApk, privateFile);
        }
        String dexPath = privateFile.getAbsolutePath();
        //Load Plugin Manifest
        PluginManifestUtil.setManifestInfo(context, dexPath, info);
        //Load Plugin Res
        try {
            AssetManager am = AssetManager.class.newInstance();
            am.getClass().getMethod("addAssetPath", String.class)
                    .invoke(am, dexPath);
            info.setAssetManager(am);
            Resources hotRes = context.getResources();
            Resources res = new Resources(am, hotRes.getDisplayMetrics(),
                    hotRes.getConfiguration());
            info.setResources(res);
        } catch (Exception e) {
            throw new RuntimeException("Unable to create Resources&Assets for "
                    + info.getPackageName() + " : " + e.getMessage());
        }
        //Load  classLoader for Plugin
        PluginClassLoader pluginClassLoader = new PluginClassLoader(info, dexPath, dexOutputPath
                , getPluginLibPath(info).getAbsolutePath(), pluginParentClassLoader);
        info.setClassLoader(pluginClassLoader);
        ApplicationInfo appInfo = info.getPackageInfo().applicationInfo;
        Application app = makeApplication(info, appInfo);
        attachBaseContext(info, app);
        info.setApplication(app);
        Trace.store("Build pluginInfo => " + info);
        return info;
    }

    private void attachBaseContext(PlugInfo info, Application app) {
        try {
            Field mBase = ContextWrapper.class.getDeclaredField("mBase");
            mBase.setAccessible(true);
            mBase.set(app, new PluginContext(context.getApplicationContext(), info));
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }


    /**
     * ?????????????????????????????????????????????????????????????????????????????????????????????????????????API.
     * @param parentClassLoader classLoader
     */
    public void setPluginParentClassLoader(ClassLoader parentClassLoader) {
        if (parentClassLoader != null) {
            this.pluginParentClassLoader = parentClassLoader;
        }else {
            this.pluginParentClassLoader = ClassLoader.getSystemClassLoader().getParent();
        }
    }

    public ClassLoader getPluginParentClassLoader() {
        return pluginParentClassLoader;
    }

    /**
     * ???????????????Application
     *
     * @param plugInfo ????????????
     * @param appInfo ??????ApplicationInfo
     * @return ??????App
     */
    private Application makeApplication(PlugInfo plugInfo, ApplicationInfo appInfo) {
        String appClassName = appInfo.className;
        if (appClassName == null) {
            //Default Application
            appClassName = Application.class.getName();
        }
            try {
                return (Application) plugInfo.getClassLoader().loadClass(appClassName).newInstance();
            } catch (Throwable e) {
                throw new RuntimeException("Unable to create Application for "
                        + plugInfo.getPackageName() + ": "
                        + e.getMessage());
            }
    }


    /**
     * ???Apk?????????????????????
     * @see PluginOverdueVerifier
     * ???????????????????????????????????????????????????????????????
     *
     * @param pluginApk    ??????apk????????????
     * @param targetPutApk ???????????????????????????
     */
    private void copyApkToPrivatePath(File pluginApk, File targetPutApk) {
        if (pluginOverdueVerifier != null) {
            //???????????????????????????????????????????????????
            if (targetPutApk.exists() && pluginOverdueVerifier.isOverdue(pluginApk, targetPutApk)) {
                return;
            }
        }
        FileUtil.copyFile(pluginApk, targetPutApk);
    }

    /**
     * @return ???????????????????????????
     */
    File getDexInternalStoragePath() {
        return dexInternalStoragePath;
    }

    Context getContext() {
        return context;
    }

    /**
     * @return ??????Activity?????????????????????
     */
    public PluginActivityLifeCycleCallback getPluginActivityLifeCycleCallback() {
        return pluginActivityLifeCycleCallback;
    }

    /**
     * ????????????Activity?????????????????????
     *
     * @param pluginActivityLifeCycleCallback ??????Activity?????????????????????
     */
    public void setPluginActivityLifeCycleCallback(
            PluginActivityLifeCycleCallback pluginActivityLifeCycleCallback) {
        this.pluginActivityLifeCycleCallback = pluginActivityLifeCycleCallback;
    }

    /**
     * @return ?????????????????????
     */
    public PluginOverdueVerifier getPluginOverdueVerifier() {
        return pluginOverdueVerifier;
    }

    /**
     * ???????????????????????????
     *
     * @param pluginOverdueVerifier ?????????????????????
     */
    public void setPluginOverdueVerifier(PluginOverdueVerifier pluginOverdueVerifier) {
        this.pluginOverdueVerifier = pluginOverdueVerifier;
    }

    @Override
    public boolean accept(File pathname) {
        return !pathname.isDirectory() && pathname.getName().endsWith(".apk");
    }


    //======================================================
    //=================????????????????????????=======================
    //======================================================


    /**
     * ??????????????????Activity
     *
     * @param from     fromContext
     * @param plugInfo ??????Info
     * @param intent   ?????????Intent?????????????????????, ?????????null
     */
    public void startMainActivity(Context from, PlugInfo plugInfo, Intent intent) {
        if (!pluginPkgToInfoMap.containsKey(plugInfo.getPackageName())) {
            return;
        }
        ActivityInfo activityInfo = plugInfo.getMainActivity().activityInfo;
        if (activityInfo == null) {
            throw new ActivityNotFoundException("Cannot find Main Activity from plugin.");
        }
        startActivity(from, plugInfo, activityInfo, intent);

    }

    /**
     * ??????????????????Activity
     *
     * @param from     fromContext
     * @param plugInfo ??????Info
     */
    public void startMainActivity(Context from, PlugInfo plugInfo) {
        startMainActivity(from, plugInfo, null);
    }

    /**
     * ??????????????????Activity
     * @param from fromContext
     * @param pluginPkgName ????????????
     * @throws PluginNotFoundException ???????????????????????????
     * @throws ActivityNotFoundException ??????Activity????????????????????????
     */
    public void startMainActivity(Context from, String pluginPkgName) throws PluginNotFoundException, ActivityNotFoundException {
        PlugInfo plugInfo = tryGetPluginInfo(pluginPkgName);
        startMainActivity(from, plugInfo);
    }


    /**
     * ?????????????????????Activity
     *
     * @param from         fromContext
     * @param plugInfo     ????????????
     * @param activityInfo ??????????????????activity??????
     * @param intent       ?????????Intent?????????????????????, ?????????null
     */
    public void startActivity(Context from, PlugInfo plugInfo, ActivityInfo activityInfo, Intent intent) {
        if (activityInfo == null) {
            throw new ActivityNotFoundException("Cannot find ActivityInfo from plugin, could you declare this Activity in plugin?");
        }
        if (intent == null) {
            intent = new Intent();
        }
        CreateActivityData createActivityData = new CreateActivityData(activityInfo.name, plugInfo.getPackageName());
        intent.setClass(from, activitySelector.selectDynamicActivity(activityInfo));
        intent.putExtra(Globals.FLAG_ACTIVITY_FROM_PLUGIN, createActivityData);
        from.startActivity(intent);
    }


    public DynamicActivitySelector getActivitySelector() {
        return activitySelector;
    }

    public void setActivitySelector(DynamicActivitySelector activitySelector) {
        if (activitySelector == null) {
            activitySelector = DefaultActivitySelector.getDefault();
        }
        this.activitySelector = activitySelector;
    }

    /**
     * ?????????????????????Activity
     *
     * @param from           fromContext
     * @param plugInfo       ????????????
     * @param targetActivity ??????????????????activity??????
     * @param intent         ?????????Intent?????????????????????, ?????????null
     */
    public void startActivity(Context from, PlugInfo plugInfo, String targetActivity, Intent intent) {
        ActivityInfo activityInfo = plugInfo.findActivityByClassName(targetActivity);
        startActivity(from, plugInfo, activityInfo, intent);
    }

    /**
     * ?????????????????????Activity
     *
     * @param from           fromContext
     * @param plugInfo       ????????????
     * @param targetActivity ??????????????????activity??????
     */
    public void startActivity(Context from, PlugInfo plugInfo, String targetActivity) {
        startActivity(from, plugInfo, targetActivity, null);
    }


    /**
     * ?????????????????????Activity
     *
     * @param from           fromContext
     * @param pluginPkgName  ????????????
     * @param targetActivity ??????????????????activity??????
     */
    public void startActivity(Context from, String pluginPkgName, String targetActivity) throws PluginNotFoundException, ActivityNotFoundException {
        startActivity(from, pluginPkgName, targetActivity, null);
    }

    /**
     * ?????????????????????Activity
     *
     * @param from           fromContext
     * @param pluginPkgName  ????????????
     * @param targetActivity ??????????????????activity??????
     * @param intent         ?????????Intent?????????????????????, ?????????null
     */
    public void startActivity(Context from, String pluginPkgName, String targetActivity, Intent intent) throws PluginNotFoundException, ActivityNotFoundException {
        PlugInfo plugInfo = tryGetPluginInfo(pluginPkgName);
        startActivity(from, plugInfo, targetActivity, intent);
    }


    public void dump() {
        Trace.store(pluginPkgToInfoMap.size() + " Plugins is loaded, " + Arrays.toString(pluginPkgToInfoMap.values().toArray()));
    }

    /**
     * @return ????????????????????????
     */
    public boolean isMainThread() {
        return Looper.getMainLooper() == Looper.myLooper();
    }
}
