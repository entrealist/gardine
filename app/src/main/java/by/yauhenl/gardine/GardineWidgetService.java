package by.yauhenl.gardine;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.ArrayDeque;
import java.util.Iterator;

public class GardineWidgetService extends AccessibilityService {

    // TODO: move to preferences
    private static final int MAX_ITEMS = 6;
    private static final int VIBRATE_DURATION = 20;

    private DiscardingStack<App> recentActivities;
    private String currentAppPackage;
    private Vibrator vibrator;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;
    private GardineView gardineView;

    public GardineWidgetService() {
        this.recentActivities = new DiscardingStack<>(MAX_ITEMS);
    }

    public int getMaxItems() {
        return MAX_ITEMS;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo config = new AccessibilityServiceInfo();
        config.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        config.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        config.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;

        setServiceInfo(config);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        Log.d(LoggingUtils.EVENT_TAG, "Window in foreground: " + event.getPackageName());
        if (event.getPackageName() == null || event.getClassName() == null) {
            return;
        }

        ComponentName componentName = new ComponentName(
                event.getPackageName().toString(),
                event.getClassName().toString()
        );

        ActivityInfo activityInfo;
        PackageManager pm = this.getPackageManager();
        try {
            activityInfo = pm.getActivityInfo(componentName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(LoggingUtils.RECENT_APPS_TAG, "Ignore window of component: " + componentName);
            return;
        }

        this.currentAppPackage = event.getPackageName().toString();

        Intent startIntent = pm.getLaunchIntentForPackage(activityInfo.packageName);
        if (startIntent == null) {
            Log.d(LoggingUtils.RECENT_APPS_TAG, "Skipping package " + activityInfo.packageName + " due to absence of a launch intent");
            return;
        }

        String label = pm.getApplicationLabel(activityInfo.applicationInfo).toString();
        Drawable icon = pm.getApplicationIcon(activityInfo.applicationInfo);
        App a = new App(label, activityInfo.packageName, icon, startIntent);
        this.recentActivities.add(a);

        Log.i(LoggingUtils.RECENT_APPS_TAG, "Added app to the stack: " + a.toLogString());
    }

    @Override
    public void onCreate() {
        super.onCreate();

        this.vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        this.gardineView = new GardineView(this);
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        applyPreferences(sharedPreferences);

        this.preferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences changedPreferences, String key) {
                applyPreferences(changedPreferences);
            }
        };
        sharedPreferences.registerOnSharedPreferenceChangeListener(this.preferenceChangeListener);
    }

    public void applyPreferences(SharedPreferences prefs) {
        applyPreferenceWidgetVisibility(prefs);
        applyPreferenceWidgetBackground(prefs);
        applyPreferenceWidgetHeight(prefs);
        applyPreferenceWidgetWidth(prefs);
        applyPreferenceWidgetPosition(prefs);
        applyPreferenceExpandedWidgetBackground(prefs);
        applyPreferenceUseIcons(prefs);
        applyPreferencePointToSelect(prefs);
    }

    public void applyPreferenceVibrateOnScroll() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        if (prefs.getBoolean(getString(R.string.pref_vibrate_on_scroll_key), true) && this.vibrator != null) {
            this.vibrator.vibrate(VIBRATE_DURATION);
        }
    }

    private void applyPreferenceWidgetVisibility(SharedPreferences sharedPreferences) {
        this.gardineView.setEnabled(sharedPreferences.getBoolean(getString(R.string.pref_widget_enabled_key), true));
    }

    private void applyPreferenceWidgetBackground(SharedPreferences prefs) {
        final int defaultColor = ContextCompat.getColor(getApplicationContext(), R.color.default_color_collapsed_widget);
        int backgroundColor = prefs.getInt(getString(R.string.pref_widget_background_color_key), defaultColor);
        this.gardineView.setCollapsedBackground(backgroundColor);
    }

    private void applyPreferenceExpandedWidgetBackground(SharedPreferences prefs) {
        final int defaultColor = ContextCompat.getColor(getApplicationContext(), R.color.default_color_expanded_widget);
        int backgroundColor = prefs.getInt(getString(R.string.pref_expanded_widget_background_color_key), defaultColor);
        this.gardineView.setExpandedBackground(backgroundColor);
    }

    private void applyPreferenceUseIcons(SharedPreferences prefs) {
        this.gardineView.setUseIcons(prefs.getBoolean(getString(R.string.pref_use_icons_key), false));
    }

    private void applyPreferencePointToSelect(SharedPreferences prefs) {
        this.gardineView.setPointToSelect(prefs.getBoolean(getString(R.string.pref_widget_point_to_select_key), false));
    }

    private void applyPreferenceWidgetHeight(SharedPreferences prefs) {
        this.gardineView.setCollapsedHeight(prefs.getInt(getString(R.string.pref_widget_height_key), 300));
    }

    private void applyPreferenceWidgetWidth(SharedPreferences prefs) {
        this.gardineView.setCollapsedWidth(prefs.getInt(getString(R.string.pref_widget_width_key), 15));
    }

    private void applyPreferenceWidgetPosition(SharedPreferences prefs) {
        WidgetPosition pos = WidgetPosition.parse(
                prefs.getString(getString(R.string.pref_widget_position_key), WidgetPosition.TOP_RIGHT.name()));
        this.gardineView.setPosition(pos);
    }

    public void actualizeRecentApps() {
        ArrayDeque<App> recentApps = this.recentActivities.getAll();
        if (this.currentAppPackage != null) {
            boolean removed = recentApps.removeFirstOccurrence(new App(this.currentAppPackage));
            Log.d(LoggingUtils.RECENT_APPS_TAG, "Current app " + this.currentAppPackage + " has been removed: " + removed);
        }
        Iterator<App> iter = recentApps.iterator();
        while (iter.hasNext()) {
            App a = iter.next();
            if (this.getPackageManager().getLaunchIntentForPackage(a.packageName) == null) {
                Log.i(LoggingUtils.RECENT_APPS_TAG, "App '" + a.title + "' cannot be resolved. Dropping it.");
                this.recentActivities.remove(a);
                iter.remove();
            }
        }

        this.gardineView.setApps(recentApps);
    }

    public void removeApp(App a) {
        this.recentActivities.remove(a);
        actualizeRecentApps();
        Toast.makeText(this,
                String.format(this.getString(R.string.error_application_not_found), a.title),
                Toast.LENGTH_SHORT)
                .show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.gardineView.destroy();
    }

    @Override
    public void onInterrupt() {

    }

}
