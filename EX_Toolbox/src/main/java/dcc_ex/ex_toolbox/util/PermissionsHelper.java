package dcc_ex.ex_toolbox.util;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
//import android.support.annotation.IntDef;
//import android.support.annotation.NonNull;
//import android.support.annotation.RequiresApi;
//import android.support.v4.app.ActivityCompat;
//import android.support.v4.content.ContextCompat;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import dcc_ex.ex_toolbox.R;

public class PermissionsHelper {

    /**
     * A compile time annotation to range-check the list of possible permission request codes.
     * Implemented this way as an Enum is 'heavier' and this checking is only really needed for
     * internal reasons.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            CONNECT_TO_SERVER,
            WRITE_SETTINGS,
            ACCESS_FINE_LOCATION,
//            NEARBY_WIFI_DEVICES,
            VIBRATE,
            READ_IMAGES,
            READ_MEDIA_IMAGES,
//            READ_LEGACY_FILES,
            POST_NOTIFICATIONS,
            READ_MEDIA_VISUAL_USER_SELECTED
    })
    public @interface RequestCodes {}

    /**
     * List of possible permission request codes
     */
    public static final int CONNECT_TO_SERVER = 40;
    public static final int WRITE_SETTINGS = 41;
    public static final int ACCESS_FINE_LOCATION = 42;
//    public static final int NEARBY_WIFI_DEVICES = 43;
    public static final int VIBRATE = 46;
    public static final int READ_IMAGES = 47;
    public static final int READ_MEDIA_IMAGES = 48;
//    public static final int READ_LEGACY_FILES = 49;
    public static final int POST_NOTIFICATIONS = 50;
    public static final int READ_MEDIA_VISUAL_USER_SELECTED = 51;

    private boolean isDialogOpen = false;
    private static PermissionsHelper instance = null;

    /**
     * Ensures only one instance of this helper exists
     *
     * @return the current instance, or a new one if not yet instantiated
     */
    public static PermissionsHelper getInstance() {
        if (instance == null) {
            instance = new PermissionsHelper();
        }
        return instance;
    }

    /**
     * Process the request permission results
     *
     * @param activity the requesting Activity
     * @param requestCode the permissions request code
     * @param permissions the permissions array
     * @param grantResults the results array
     * @return true if recognised permissions request
     */
    public boolean processRequestPermissionsResult(final Activity activity, @RequestCodes int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        boolean isRecognised = false;

        // process the resultCode array
        for (int i=0; i<permissions.length; i++) {
            int grantResult = grantResults[i];

            if (!showPermissionRationale(activity, requestCode) && grantResult != PackageManager.PERMISSION_GRANTED) {
                isRecognised = true;
                Log.d("EX_Toolbox", "Permission denied - showAppSettingsDialog");
                showAppSettingsDialog(activity, requestCode);
                break;
            } else if (grantResult != PackageManager.PERMISSION_GRANTED) {
                isRecognised = true;
                Log.d("EX_Toolbox", "Permission denied - showRetryDialog");
                if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    showRetryDialog(activity, requestCode);
                } else {
                    showAppSettingsDialog(activity, requestCode, true);
                }
                break;
            } else {
                isRecognised = true;
                Log.d("EX_Toolbox", "Permission granted - navigateToHandler");
                ((PermissionsHelperGrantedCallback) activity).navigateToHandler(requestCode);
            }
        }

        //context.navigateToHandler(requestCode, resultCode);
        return isRecognised;
    }

    /**
     * Internal method to retrieve the appropriate message for retry and rationale dialogs
     *
     * @param context the requesting Activity's context
     * @param requestCode the permissions request code
     * @return
     */
    private String getMessage(final Context context, @RequestCodes final int requestCode) {
        // Get the relevant rationale message based on request code
        // All possible request codes should be considered
        switch (requestCode) {
            case READ_IMAGES:
                return context.getResources().getString(R.string.permissionsReadImages);
            case READ_MEDIA_IMAGES:
                return context.getResources().getString(R.string.permissionsREAD_MEDIA_IMAGES);
            case READ_MEDIA_VISUAL_USER_SELECTED: // needed for API 34
                return context.getResources().getString(R.string.permissionsREAD_MEDIA_VISUAL_USER_SELECTED);
            case CONNECT_TO_SERVER:
                return context.getResources().getString(R.string.permissionsConnectToServer);
            case WRITE_SETTINGS:
                return context.getResources().getString(R.string.permissionsWriteSettings);
            case ACCESS_FINE_LOCATION:
                return context.getResources().getString(R.string.permissionsACCESS_FINE_LOCATION);
//            case NEARBY_WIFI_DEVICES:
//                return context.getResources().getString(R.string.permissionsNEARBY_WIFI_DEVICES);
            case VIBRATE:
                return context.getResources().getString(R.string.permissionsVIBRATE);
            case POST_NOTIFICATIONS:
                return context.getResources().getString(R.string.permissionsPOST_NOTIFICATIONS);
            default:
                return "Unknown permission request: " + requestCode;
        }
    }

    /**
     * Method to request the necessary permissions
     *
     * @param activity the requesting Activity
     * @param requestCode the permissions request code
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    public void requestNecessaryPermissions(final Activity activity, @RequestCodes final int requestCode) {
        // Request the necessary permissions based on request code
        // All possible request codes should be considered
        Log.d("EX_Toolbox", "isDialogOpen at requestNecessaryPermissions? " + isDialogOpen);
        if (!isDialogOpen) {
            switch (requestCode) {
//                case READ_LEGACY_FILES:
                case READ_IMAGES:
                    Log.d("EX_Toolbox", "Requesting READ_EXTERNAL_STORAGE permissions");
                    activity.requestPermissions(new String[]{
                                    Manifest.permission.READ_EXTERNAL_STORAGE},
                            requestCode);
                    break;
                case READ_MEDIA_IMAGES: // needed for API 33
                    Log.d("EX_Toolbox", "Requesting READ_MEDIA_IMAGES permissions");
                    activity.requestPermissions(new String[]{
                                    Manifest.permission.READ_MEDIA_IMAGES},
                            requestCode);
                    break;
                case READ_MEDIA_VISUAL_USER_SELECTED: // needed for API 34
                    Log.d("EX_Toolbox", "Requesting READ_MEDIA_VISUAL_USER_SELECTED permissions");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        activity.requestPermissions(new String[]{
                                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},
                                requestCode);
                    }
                    break;
                case ACCESS_FINE_LOCATION:
                    Log.d("EX_Toolbox", "Requesting ACCESS_FINE_LOCATION permissions");
                    activity.requestPermissions(new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION},
                            requestCode);
                    Log.d("EX_Toolbox", "Requesting ACCESS_FINE_LOCATION permissions");
                    break;
//                case NEARBY_WIFI_DEVICES:
//                    activity.requestPermissions(new String[]{
//                                    Manifest.permission.NEARBY_WIFI_DEVICES},
//                            requestCode);
//                    Log.d("EX_Toolbox", "Requesting NEARBY_WIFI_DEVICES permissions");
//                    break;
                case CONNECT_TO_SERVER:
                    Log.d("EX_Toolbox", "Requesting PHONE permission");
//                    activity.requestPermissions(new String[]{
//                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
//                                    Manifest.permission.READ_EXTERNAL_STORAGE,
//                    requestCode);
//                    activity.requestPermissions(new String[]{
//                                    Manifest.permission.READ_PHONE_STATE},
//                            requestCode);
                    break;
                case WRITE_SETTINGS:
                    Log.d("EX_Toolbox", "Requesting WRITE_SETTINGS permissions");
                    if (android.os.Build.VERSION.SDK_INT < 23) {
                        activity.requestPermissions(new String[]{
                                        Manifest.permission.WRITE_SETTINGS},
                                requestCode);
                    } else {
                        if (!Settings.System.canWrite(activity)) {
                            showAppSettingsDialog(activity, requestCode);
                        }
                    }
                    break;
                case VIBRATE:
                    Log.d("EX_Toolbox", "Requesting VIBRATE permissions");
                    activity.requestPermissions(new String[]{
                                    Manifest.permission.VIBRATE},
                            requestCode);
                    break;
                case POST_NOTIFICATIONS:
                    Log.d("EX_Toolbox", "Requesting POST_NOTIFICATIONS permissions");
                    activity.requestPermissions(new String[]{
                                    Manifest.permission.POST_NOTIFICATIONS},
                            requestCode);
                    break;
            }
        } else {
            Log.d("EX_Toolbox", "Permissions dialog is opened - don't ask yet...");
        }
    }

    /**
     * Internal method to display a link to the application settings when permissions request denied
     * and a retry is no longer possible
     *
     * @param context the requesting Activity's context
     * @param requestCode the permissions request code
     */
    private void showAppSettingsDialog(final Context context, @RequestCodes final int requestCode) {
        showAppSettingsDialog(context, requestCode, false);
    }
    private void showAppSettingsDialog(final Context context, @RequestCodes final int requestCode, boolean retry) {
        String positiveButtonLabel;
        String title = context.getResources().getString(R.string.permissionsRequestTitle);
        if (requestCode != WRITE_SETTINGS) {
            positiveButtonLabel = context.getResources().getString(R.string.permissionsAppSettingsButton);
        } else {
            positiveButtonLabel = context.getResources().getString(R.string.permissionsSystemSettingsButton);
        }
        if (!retry) {
            title = context.getResources().getString(R.string.permissionsRetryTitle);
        }
        isDialogOpen = true;
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(getMessage(context, requestCode))
                .setPositiveButton(positiveButtonLabel, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialogInterface, int i) {
                        Intent intent = new Intent();
                        if (requestCode != WRITE_SETTINGS) {
                            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        } else {
                            intent.setAction(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                        }
                        Uri uri = Uri.fromParts("package", context.getApplicationContext().getPackageName(), null);
                        intent.setData(uri);
                        context.startActivity(intent);
                        isDialogOpen = false;
                    }
                })
                .setNegativeButton(context.getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialogInterface, int i) {
                        isDialogOpen = false;
                    }
                }).create().show();
    }

    /**
     * Internal method to display a retry dialog when permissions request denied
     *
     * @param context the requesting Activity's context
     * @param requestCode the permissions request code
     */
    private void showRetryDialog(final Context context, @RequestCodes final int requestCode) {
        isDialogOpen = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            new AlertDialog.Builder(context)
                    .setTitle(context.getResources().getString(R.string.permissionsRetryTitle))
                    .setMessage(getMessage(context, requestCode))
                    .setPositiveButton(context.getResources().getString(R.string.permissionsRetryButton), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            isDialogOpen = false;
                            requestNecessaryPermissions((Activity) context, requestCode);
                        }
                    })
                    .setNegativeButton(context.getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            isDialogOpen = false;
                        }
                    }).create().show();
        } else {
            new AlertDialog.Builder(context)
                    .setTitle(context.getResources().getString(R.string.permissionsRetryTitle))
                    .setMessage(getMessage(context, requestCode))
                    .setNegativeButton(context.getResources().getString(R.string.cancel), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            isDialogOpen = false;
                        }
                    }).create().show();
        }
    }

    /**
     * Determines if requested permissions have been granted
     *
     * @param context the requesting Activity's context
     * @param requestCode the permissions request code
     * @return true if permissions granted; false if not
     */
//    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @SuppressLint("ObsoleteSdkInt")
    public boolean isPermissionGranted(final Context context, @RequestCodes final int requestCode) {
        //sdk 15 doesn't support some of the codes below, always return success
        if (android.os.Build.VERSION.SDK_INT < 16) {
            return true;
        }
        // Determine which permissions to check based on request code
        // All possible request codes should be considered
        switch (requestCode) {
//            case READ_LEGACY_FILES:
//                return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            case READ_IMAGES:
                return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            case READ_MEDIA_IMAGES:
                return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            case CONNECT_TO_SERVER:
                  return  ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
            case ACCESS_FINE_LOCATION :
                return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION ) == PackageManager.PERMISSION_GRANTED;
//            case NEARBY_WIFI_DEVICES :
//                return ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES ) == PackageManager.PERMISSION_GRANTED;
            case WRITE_SETTINGS:
                boolean result;
                if (android.os.Build.VERSION.SDK_INT < 23) {
                    result = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
                } else {
                    result = Settings.System.canWrite(context);
                }
                return result;
            case VIBRATE:
                return ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED;
            case POST_NOTIFICATIONS:
                return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS ) == PackageManager.PERMISSION_GRANTED;
            default:
                return false;
        }
    }

    /**
     * Internal method to determine if to show the permission rationale message
     *
     * @param activity the requesting Activity
     * @param requestCode the permissions request code
     * @return true if rationale to be shown; false if not
     */
//    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private boolean showPermissionRationale(final Activity activity, @RequestCodes final int requestCode) {
        // Determine which permission rationales to check based on request code
        // All possible request codes should be considered
        switch (requestCode) {
//            case READ_LEGACY_FILES:
//                return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE);
            case READ_IMAGES:
                return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE);
            case READ_MEDIA_IMAGES:
                return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_MEDIA_IMAGES);
            case CONNECT_TO_SERVER:
                return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_PHONE_STATE);
            case ACCESS_FINE_LOCATION:
                return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION);
//            case NEARBY_WIFI_DEVICES:
//                return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.NEARBY_WIFI_DEVICES);
            case WRITE_SETTINGS:
                return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_SETTINGS);
            case POST_NOTIFICATIONS:
                return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS);
            default:
                return false;
        }
    }


    // don't know why but the isDialogOpen variable can get stuck on true
    // use this to initialise it
    public void setIsDialogOpen(boolean isOpen) {
        isDialogOpen = isOpen;
    }

    static public String getManifestPermissionId(@RequestCodes final int requestCode) {
        switch (requestCode) {
            case READ_IMAGES:
                return Manifest.permission.READ_EXTERNAL_STORAGE;
            case ACCESS_FINE_LOCATION:
                return Manifest.permission.ACCESS_FINE_LOCATION;
            case WRITE_SETTINGS:
                return Manifest.permission.WRITE_SETTINGS;
            case READ_MEDIA_IMAGES:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return Manifest.permission.READ_MEDIA_IMAGES;
                } else { return "";}
            case READ_MEDIA_VISUAL_USER_SELECTED:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    return Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED;
                } else { return "";}
            case POST_NOTIFICATIONS:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return Manifest.permission.POST_NOTIFICATIONS;
                } else { return "";}
            default:
                return "";
        }
    }

    /**
     * Callback interface to be implemented by any calling Activity
     */
    public interface PermissionsHelperGrantedCallback {
        void navigateToHandler(@RequestCodes int requestCode); //, @ResultCode int resultCode);
    }
}
