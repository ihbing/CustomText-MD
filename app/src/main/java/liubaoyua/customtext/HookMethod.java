package liubaoyua.customtext;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.text.TextUtils;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import liubaoyua.customtext.entity.CustomText;
import liubaoyua.customtext.utils.Common;
import liubaoyua.customtext.utils.XposedUtil;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;


public class HookMethod implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    private XSharedPreferences prefs;
    private Html.ImageGetter imageGetter = new Html.ImageGetter() {
        public Drawable getDrawable(String source) {
            Drawable d = Drawable.createFromPath(source);
            d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            return d;
        }
    };

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        prefs = new XSharedPreferences(Common.PACKAGE_NAME);
        prefs.makeWorldReadable();
    }

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {

        XposedBridge.log("Custom Text: in package:" + lpparam.packageName);

        prefs.reload();
        if (!prefs.getBoolean(Common.SETTING_MODULE_SWITCH, true)) {
            return;
        }

        XSharedPreferences mPrefs = new XSharedPreferences(Common.PACKAGE_NAME, lpparam.packageName);
        mPrefs.makeWorldReadable();
        XSharedPreferences sPrefs = new XSharedPreferences(Common.PACKAGE_NAME, Common.SHARING_SETTING_PACKAGE_NAME);
        sPrefs.makeWorldReadable();

        final boolean isInDebugMode = prefs.getBoolean(Common.SETTING_XPOSED_DEBUG_MODE, Common.XPOSED_DEBUG);

        final boolean shouldUseHtml;
        if (mPrefs.contains(Common.SETTING_MORE_TYPE)) {
            shouldUseHtml = mPrefs.getBoolean(Common.SETTING_USE_HTML, false);
        } else {
            shouldUseHtml = prefs.getBoolean(Common.SETTING_USE_HTML, false);
        }

        final boolean shouldHackMoreType;
        if (mPrefs.contains(Common.SETTING_MORE_TYPE)) {
            shouldHackMoreType = mPrefs.getBoolean(Common.SETTING_MORE_TYPE, false);
        } else {
            shouldHackMoreType = prefs.getBoolean(Common.SETTING_MORE_TYPE, false);
        }

        final boolean shouldUseRegex;
        if (mPrefs.contains(Common.SETTING_MORE_TYPE)) {
            shouldUseRegex = mPrefs.getBoolean(Common.SETTING_USE_REGEX, false);
        } else {
            shouldUseRegex = prefs.getBoolean(Common.SETTING_USE_REGEX, false);
        }

        final boolean isGlobalHackEnabled = prefs.getBoolean(Common.PREFS, false);
        final boolean isCurrentHackEnabled = prefs.getBoolean(lpparam.packageName, false);
        final boolean isInThisApp = lpparam.packageName.equals(Common.PACKAGE_NAME);
        final boolean isSharedHackEnabled = prefs.getBoolean(Common.SHARING_SETTING_PACKAGE_NAME, false);

        final String thisAppName = prefs.getString(Common.PACKAGE_NAME_ARG, Common.DEFAULT_APP_NAME);

        if (isInDebugMode) {
            JSONObject object = new JSONObject();
            XposedBridge.log("Custom Text Debugging...");
            object.put("packageName", lpparam.packageName)
                    .put("isGlobalHackEnabled", isGlobalHackEnabled)
                    .put("isCurrentHackEnabled", isCurrentHackEnabled)
                    .put("isSharedHackEnabled", isSharedHackEnabled)
                    .put("shouldUseRegex", shouldUseRegex)
                    .put("shouldHackMoreType", shouldHackMoreType)
                    .put("shouldUseHtml", shouldUseHtml);
            try {
                XposedBridge.log(object.toString(4));
            } catch (Throwable e) {
            }
        }

        XC_MethodHook textMethodHook;
        if (isInThisApp) {
            final String hackSucceedMessage;
            String temp = prefs.getString(Common.SETTING_HACK_SUCCEED_MESSAGE, Common.DEFAULT_MESSAGE);

            if (temp.equals("")) {
                hackSucceedMessage = prefs.getString(Common.MESSAGE, Common.DEFAULT_MESSAGE);
            } else {
                hackSucceedMessage = temp;
            }

            textMethodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    if (methodHookParam.args[0] instanceof String) {
                        String abc = (String) methodHookParam.args[0];
                        abc = abc.replaceAll(thisAppName, hackSucceedMessage);
                        methodHookParam.args[0] = abc;
                    }
                }
            };
            findAndHookMethod(XposedUtil.class.getName(), lpparam.classLoader,
                    "isXposedEnable", XC_MethodReplacement.returnConstant(true));
        } else {
            if (!isGlobalHackEnabled && !isCurrentHackEnabled)
                return;

            final CustomText[] current = loadCustomTextArrayFromPrefs(mPrefs, isCurrentHackEnabled);
            final CustomText[] shared = loadCustomTextArrayFromPrefs(sPrefs, isSharedHackEnabled && isCurrentHackEnabled);
            final CustomText[] global = loadCustomTextArrayFromPrefs(prefs, isGlobalHackEnabled);

            textMethodHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    String source;
                    if (shouldHackMoreType && methodHookParam.args[0] != null) {
                        source = methodHookParam.args[0].toString();
                    } else if (!shouldHackMoreType && methodHookParam.args[0] instanceof String) {
                        source = (String) methodHookParam.args[0];
                    } else {
                        return;
                    }

                    Result result = new Result(source, false);
                    if (isCurrentHackEnabled) {
                        replaceAllFromArray(current, result, shouldUseRegex);
                    }
                    if (isSharedHackEnabled && isCurrentHackEnabled) {
                        replaceAllFromArray(shared, result, shouldUseRegex);
                    }
                    if (isGlobalHackEnabled) {
                        replaceAllFromArray(global, result, shouldUseRegex);
                    }

                    if (result.isChange()) {
                        // changed... so we reset the arg[0]...
                        if (shouldUseHtml) {
                            setTextFromHtml(result.getText(), methodHookParam, 0);
                        } else {
                            methodHookParam.args[0] = result.getText();
                        }
                        if (isInDebugMode) {
                            try {
                                JSONObject o = new JSONObject();
                                o.put("package", lpparam.packageName)
                                        .put("source", source)
                                        .put("after", result.getText());
                                XposedBridge.log("Custom Text... Replace text in Package: \n" + o.toString(4));
                            } catch (Throwable e) {
                                //
                            }
                        }
                    }
                }
            };
        }

        findAndHookMethod(TextView.class, "setText", CharSequence.class,
                TextView.BufferType.class, boolean.class, int.class, textMethodHook);
        findAndHookMethod(TextView.class, "setHint", CharSequence.class, textMethodHook);
        try {
            findAndHookMethod(Canvas.class, "drawText", String.class,
                    float.class, float.class, Paint.class, textMethodHook);
        } catch (Throwable e) {
            //ignore...
        }

        if (isInDebugMode) {
            XposedBridge.log("Custom Text..." + thisAppName + ":  findAndHookDone");
        }
    }

    private void setTextFromHtml(String html, XC_MethodHook.MethodHookParam param, int n) {
        if (html.contains("<") && html.contains(">")) {
            CharSequence text = Html.fromHtml(html, imageGetter, null);
            if (param.thisObject instanceof TextView)
                param.args[n] = text;
            else
                param.args[n] = text.toString();
        } else
            param.args[n] = html;
    }

    public static void replaceAllFromArray(CustomText[] customTexts, Result result, boolean regex) {
        if (customTexts == null) {
            return;
        }
        for (CustomText customText : customTexts) {
            if (regex) {
                result = patternReplace(result, customText.getPattern(), customText.getNewText());
            } else {
                result = normalReplace(result, customText.getOriText(), customText.getNewText());
            }
        }
    }

    public static CustomText[] loadCustomTextArrayFromPrefs(XSharedPreferences prefs, Boolean enabled) {
        if (!enabled) {
            return null;
        }
        List<CustomText> list = new ArrayList<>();
        final int num = (prefs.getInt(Common.MAX_PAGE_OLD, 0) + 1) * Common.DEFAULT_NUM;
        for (int i = 0; i < num; i++) {
            String oriString = prefs.getString(Common.ORI_TEXT_PREFIX + i, "");
            String newString = prefs.getString(Common.NEW_TEXT_PREFIX + i, "");
            if (!TextUtils.isEmpty(oriString)) {
                CustomText customText = new CustomText(oriString, newString);
                list.add(customText);
            }
        }
        return list.toArray(new CustomText[list.size()]);
    }


    public static Result patternReplace(Result origin, Pattern target, String replacement) {
        String text = origin.getText();
        Matcher matcher = target.matcher(text);
        StringBuffer buffer = null;
        boolean change = false;
        while (matcher.find()) {
            if (buffer == null) {
                buffer = new StringBuffer(text.length());
            }
            matcher.appendReplacement(buffer, replacement);
            change = true;
        }
        if (change) {
            origin.setText(matcher.appendTail(buffer).toString());
        }
        return origin;
    }

    public static Result normalReplace(Result origin, CharSequence target, CharSequence replacement) {
        String text = origin.getText();

        String targetString = target.toString();
        int matchStart = text.indexOf(targetString, 0);
        if (matchStart == -1) {
            // If there's nothing to replace, return the original string untouched.
            return origin;
        }

        String replacementString = replacement.toString();

        // The empty target matches at the start and end and between each char.
        int targetLength = targetString.length();
        if (targetLength == 0) {
            int resultLength = text.length() + (text.length() + 1) * replacementString.length();
            StringBuilder result = new StringBuilder(resultLength);
            result.append(replacementString);
            for (int i = 0; i != text.length(); ++i) {
                result.append(text.charAt(i));
                result.append(replacementString);
            }
            origin.setText(result.toString());
            return origin;
        }

        StringBuilder result = new StringBuilder(text);
        int searchStart = 0;
        do {
            // Copy characters before the match...
            // TODO: Perform this faster than one char at a time?
            for (int i = searchStart; i < matchStart; ++i) {
                result.append(text.charAt(i));
            }
            // Insert the replacement...
            result.append(replacementString);
            // And skip over the match...
            searchStart = matchStart + targetLength;
        } while ((matchStart = text.indexOf(targetString, searchStart)) != -1);
        // Copy any trailing chars...
        // TODO: Perform this faster than one char at a time?
        for (int i = searchStart; i < text.length(); ++i) {
            result.append(text.charAt(i));
        }
        origin.setText(result.toString());
        return origin;
    }
}