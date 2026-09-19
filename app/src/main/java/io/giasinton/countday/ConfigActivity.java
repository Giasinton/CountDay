package io.giasinton.countday;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;

/**
 * 小组件配置 / 编辑页。
 *
 * <p>这是应用中唯一的 Activity，且没有 MAIN/LAUNCHER 入口，
 * 所以桌面上既没有图标，也没有可以主动打开的界面；它只会被两种情况唤起：
 * <ul>
 *   <li>把小组件拖到桌面时（系统带 APPWIDGET_CONFIGURE 启动）；</li>
 *   <li>点击或长按已有小组件"编辑"时。</li>
 * </ul>
 */
public class ConfigActivity extends Activity {

    private static final String TAG = "CountDay";

    private static final int REQ_PICK_IMAGE = 1001;

    /** 打开配置页用的 Intent（连点三下、长按「设置」都走这里）。 */
    static Intent editIntent(Context context, int widgetId) {
        Intent intent = new Intent(context, ConfigActivity.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return intent;
    }

    private static final int IMAGE_KEEP = 0;
    private static final int IMAGE_NEW = 1;
    private static final int IMAGE_REMOVE = 2;

    /** 调色板当前编辑的对象。 */
    private static final int TARGET_BG = 0;
    private static final int TARGET_TEXT = 1;

    /** 预览卡片最大边长（dp）。 */
    private static final int PREVIEW_MAX_DP = 150;

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private EditText titleInput;
    private RadioButton countdownOption;
    private RadioButton countUpOption;
    private TextView dateLabel;
    private Button dateButton;
    private Button clearButton;

    private TextView colorLabel;
    private View colorPreview;
    private TextView colorHex;
    private CheckBox themeColorCheck;
    private RadioButton targetBg;
    private RadioButton targetText;
    private ColorPickerView colorPicker;
    private SeekBar hueSlider;
    private SeekBar alphaSlider;
    private TextView alphaLabel;

    private RadioButton sizeFill;
    private RadioButton sizeCustom;
    private RadioButton sizeImage;
    private SeekBar widthSlider;
    private SeekBar heightSlider;
    private TextView widthLabel;
    private TextView heightLabel;
    private CheckBox squareLock;
    private SeekBar radiusSlider;
    private TextView radiusLabel;

    private RadioButton vTop;
    private RadioButton vCenter;
    private RadioButton vBottom;
    private RadioButton alignLeft;
    private RadioButton alignCenter;
    private RadioButton alignRight;
    private SeekBar textSizeSlider;
    private TextView textSizeLabel;
    private SeekBar offsetXSlider;
    private SeekBar offsetYSlider;
    private TextView offsetXLabel;
    private TextView offsetYLabel;

    private TextView imageStatus;
    private TextView imageWarning;
    private Button cropButton;
    private CropImageView cropView;
    private View cropActions;
    private FrameLayout previewCard;
    private ImageView previewImage;
    private LinearLayout previewText;
    private TextView previewTitle;
    private LinearLayout previewNumberRow;
    private TextView previewNumber;
    private TextView previewUnit;
    private TextView previewDate;

    private long selectedEpochDay = LocalDate.now().toEpochDay();
    private boolean existing;

    private boolean customBg;
    private int bgRgb = 0xFFFFFF;
    private int bgAlpha = 0xF2;

    private boolean customTextColor;
    private int textRgb = 0x000000;
    private int textAlpha = 0xDE;

    private int colorTarget = TARGET_BG;

    private int textVPos = WidgetData.VPOS_CENTER;
    private int textAlign = WidgetData.ALIGN_CENTER;
    private int textScalePercent = 100;
    private int textOffsetXPercent = 0;
    private int textOffsetYPercent = 0;

    private int sizeMode = WidgetData.SIZE_FILL;
    private int sizePercentW = 100;
    private int sizePercentH = 100;
    private int cornerRadiusDp = Math.round(WidgetUi.DEFAULT_RADIUS_DP);

    private boolean hadImage;
    private Bitmap sourceBitmap;
    private Bitmap previewBitmap;
    private int imageState = IMAGE_KEEP;
    private boolean cropVisible;
    private float previewScale = 1f;
    private int previewWidthPx = 1;
    private int previewHeightPx = 1;

    /** 正在等系统选图器返回。这一段时间里绝不能把自己 finish 掉。 */
    private boolean pickingImage;

    /** 同步界面控件时抑制回调，避免互相触发。 */
    private boolean syncing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        widgetId = getIntent().getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        // 默认返回"取消"：用户按返回键时不会新建/修改小组件
        setResult(RESULT_CANCELED, resultIntent());

        setContentView(R.layout.activity_config);

        titleInput = findViewById(R.id.et_title);
        countdownOption = findViewById(R.id.rb_countdown);
        countUpOption = findViewById(R.id.rb_count_up);
        dateLabel = findViewById(R.id.tv_date_label);
        dateButton = findViewById(R.id.btn_date);
        clearButton = findViewById(R.id.btn_clear);

        colorLabel = findViewById(R.id.tv_color_label);
        colorPreview = findViewById(R.id.view_color_preview);
        colorHex = findViewById(R.id.tv_color_hex);
        themeColorCheck = findViewById(R.id.cb_theme_color);
        targetBg = findViewById(R.id.rb_target_bg);
        targetText = findViewById(R.id.rb_target_text);
        colorPicker = findViewById(R.id.color_picker);
        hueSlider = findViewById(R.id.sb_hue);
        alphaSlider = findViewById(R.id.sb_alpha);
        alphaLabel = findViewById(R.id.tv_alpha);

        sizeFill = findViewById(R.id.rb_size_fill);
        sizeCustom = findViewById(R.id.rb_size_custom);
        sizeImage = findViewById(R.id.rb_size_image);
        widthSlider = findViewById(R.id.sb_width);
        heightSlider = findViewById(R.id.sb_height);
        widthLabel = findViewById(R.id.tv_width);
        heightLabel = findViewById(R.id.tv_height);
        squareLock = findViewById(R.id.cb_square);
        radiusSlider = findViewById(R.id.sb_radius);
        radiusLabel = findViewById(R.id.tv_radius);

        vTop = findViewById(R.id.rb_vtop);
        vCenter = findViewById(R.id.rb_vcenter);
        vBottom = findViewById(R.id.rb_vbottom);
        alignLeft = findViewById(R.id.rb_aleft);
        alignCenter = findViewById(R.id.rb_acenter);
        alignRight = findViewById(R.id.rb_aright);
        textSizeSlider = findViewById(R.id.sb_text_size);
        textSizeLabel = findViewById(R.id.tv_text_size);
        offsetXSlider = findViewById(R.id.sb_offset_x);
        offsetYSlider = findViewById(R.id.sb_offset_y);
        offsetXLabel = findViewById(R.id.tv_offset_x);
        offsetYLabel = findViewById(R.id.tv_offset_y);

        imageStatus = findViewById(R.id.tv_image_status);
        imageWarning = findViewById(R.id.tv_image_warning);
        cropButton = findViewById(R.id.btn_crop);
        cropView = findViewById(R.id.crop_view);
        cropActions = findViewById(R.id.crop_actions);
        previewCard = findViewById(R.id.preview_card);
        previewImage = findViewById(R.id.preview_image);
        previewText = findViewById(R.id.preview_text);
        previewTitle = findViewById(R.id.preview_title);
        previewNumber = findViewById(R.id.preview_number);
        previewNumberRow = findViewById(R.id.preview_number_row);
        previewUnit = findViewById(R.id.preview_unit);
        previewDate = findViewById(R.id.preview_date);

        loadData();
        setupColorUi();
        setupSizeUi();
        setupRadiusUi();
        setupTextUi();
        setupImageUi();

        colorPicker.setOnColorChangedListener(color -> onColorPicked());
        cropView.setOnCropListener(this::onCropChanged);

        restoreState(savedInstanceState);
        syncColorUi();
        syncSizeUi();
        syncRadiusUi();
        syncTextUi();
        applyPreviewSize();
        updateAlphaLabel();
        updateTextSizeLabel();
        refreshDateUi();
        refreshImageStatus();
    }

    // ------------------------------------------------------- 状态保存/恢复

    private static final String STATE = "ui";

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Bundle b = new Bundle();
        b.putBoolean("customBg", customBg);
        b.putInt("bgRgb", bgRgb);
        b.putInt("bgAlpha", bgAlpha);
        b.putBoolean("customTextColor", customTextColor);
        b.putInt("textRgb", textRgb);
        b.putInt("textAlpha", textAlpha);
        b.putInt("textVPos", textVPos);
        b.putInt("textAlign", textAlign);
        b.putInt("textScale", textScalePercent);
        b.putInt("offsetX", textOffsetXPercent);
        b.putInt("offsetY", textOffsetYPercent);
        b.putInt("sizeMode", sizeMode);
        b.putInt("sizePercentW", sizePercentW);
        b.putInt("sizePercentH", sizePercentH);
        b.putInt("cornerRadius", cornerRadiusDp);
        b.putInt("colorTarget", colorTarget);
        b.putInt("imageState", imageState);
        b.putBoolean("cropVisible", cropVisible);
        b.putFloatArray("crop", cropView.getCropRect());
        outState.putBundle(STATE, b);
    }

    /** 被系统重建时，把上次的编辑状态原样接回来（而不是回到初始值、滚动到顶部）。 */
    private void restoreState(Bundle savedInstanceState) {
        Bundle b = savedInstanceState == null ? null : savedInstanceState.getBundle(STATE);
        if (b == null) {
            return;
        }
        customBg = b.getBoolean("customBg", customBg);
        bgRgb = b.getInt("bgRgb", bgRgb);
        bgAlpha = b.getInt("bgAlpha", bgAlpha);
        customTextColor = b.getBoolean("customTextColor", customTextColor);
        textRgb = b.getInt("textRgb", textRgb);
        textAlpha = b.getInt("textAlpha", textAlpha);
        textVPos = b.getInt("textVPos", textVPos);
        textAlign = b.getInt("textAlign", textAlign);
        textScalePercent = b.getInt("textScale", textScalePercent);
        textOffsetXPercent = b.getInt("offsetX", textOffsetXPercent);
        textOffsetYPercent = b.getInt("offsetY", textOffsetYPercent);
        sizeMode = b.getInt("sizeMode", sizeMode);
        sizePercentW = b.getInt("sizePercentW", sizePercentW);
        sizePercentH = b.getInt("sizePercentH", sizePercentH);
        cornerRadiusDp = b.getInt("cornerRadius", cornerRadiusDp);
        colorTarget = b.getInt("colorTarget", colorTarget);
        imageState = b.getInt("imageState", imageState);
        cropVisible = b.getBoolean("cropVisible", cropVisible);
        float[] crop = b.getFloatArray("crop");
        if (crop != null && crop.length == 4 && sourceBitmap != null) {
            cropView.setCropRect(crop[0], crop[1], crop[2], crop[3]);
        }
    }

    // ---------------------------------------------------------------- 数据

    private void loadData() {
        WidgetData data = WidgetStore.load(this, widgetId);
        existing = data != null;
        if (existing) {
            titleInput.setText(data.title);
            selectedEpochDay = data.dateEpochDay;
            countUpOption.setChecked(data.mode == WidgetData.MODE_COUNT_UP);
            countdownOption.setChecked(data.mode != WidgetData.MODE_COUNT_UP);
            customBg = data.customBg;
            if (data.customBg) {
                bgRgb = data.bgColor & 0xFFFFFF;
                bgAlpha = Color.alpha(data.bgColor);
            } else {
                bgAlpha = Color.alpha(getColor(R.color.widget_bg));
            }
            customTextColor = data.customTextColor;
            if (data.customTextColor) {
                textRgb = data.textColor & 0xFFFFFF;
                textAlpha = Color.alpha(data.textColor);
            } else {
                textAlpha = Color.alpha(getColor(R.color.widget_text_primary));
                textRgb = getColor(R.color.widget_text_primary) & 0xFFFFFF;
            }
            textVPos = data.textVPos;
            textAlign = data.textAlign;
            textScalePercent = data.textScalePercent;
            textOffsetXPercent = data.textOffsetXPercent;
            textOffsetYPercent = data.textOffsetYPercent;
            sizeMode = data.sizeMode == WidgetData.SIZE_SQUARE
                    ? WidgetData.SIZE_CUSTOM : data.sizeMode;
            sizePercentW = WidgetUi.clampPercent(data.sizePercentW);
            sizePercentH = WidgetUi.clampPercent(data.sizePercentH);
            cornerRadiusDp = Math.round(WidgetUi.clampRadiusDp(data));
            hadImage = data.hasImage;
            if (hadImage) {
                sourceBitmap = ImageStore.loadSource(this, widgetId, ImageStore.storeMaxPx());
                hadImage = sourceBitmap != null;
                // 只有成品图、没有原图（原图被清理过）：裁剪会基于成品图，画质会差一点
                imageWarning.setVisibility(
                        ImageStore.hasSource(this, widgetId) ? View.GONE : View.VISIBLE);
                if (sourceBitmap != null) {
                    cropView.setBitmap(sourceBitmap);
                    cropView.setCropRect(data.cropLeft, data.cropTop,
                            data.cropRight, data.cropBottom);
                    previewBitmap = ImageStore.load(this, widgetId, ImageStore.storeMaxPx());
                }
            }
            setTitle(R.string.config_title_edit);
        } else {
            countdownOption.setChecked(true);
            bgAlpha = Color.alpha(getColor(R.color.widget_bg));
            textAlpha = Color.alpha(getColor(R.color.widget_text_primary));
            textRgb = getColor(R.color.widget_text_primary) & 0xFFFFFF;
            setTitle(R.string.config_title_new);
        }
        clearButton.setVisibility(existing ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- 颜色

    private void setupColorUi() {
        colorPicker.setColor(0xFFFFFFFF);

        View hueBar = findViewById(R.id.hue_bar);
        GradientDrawable rainbow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {
                        0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                        0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
                });
        rainbow.setCornerRadius(dp(5));
        hueBar.setBackground(rainbow);

        targetBg.setOnClickListener(view -> switchColorTarget(TARGET_BG));
        targetText.setOnClickListener(view -> switchColorTarget(TARGET_TEXT));

        hueSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                colorPicker.setHue(progress);
                if (fromUser && !syncing) {
                    onColorPicked();
                }
            }
        });

        themeColorCheck.setOnCheckedChangeListener((CompoundButton buttonView, boolean checked) -> {
            if (syncing) {
                return;
            }
            setTargetCustom(!checked);
            syncColorUi();
            refreshPreview();
        });

        alphaSlider.setMax(100);
        alphaSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setTargetAlpha(Math.round(progress * 255f / 100f));
                if (fromUser && !isTargetCustom()) {
                    // 默认色是"跟随主题"的，一旦要调透明度就固定成取色器当前的颜色
                    setTargetCustom(true);
                    setTargetRgb(colorPicker.getColor() & 0xFFFFFF);
                    syncing = true;
                    themeColorCheck.setChecked(false);
                    syncing = false;
                }
                updateAlphaLabel();
                updateColorPreview();
                refreshPreview();
            }
        });
    }

    private void switchColorTarget(int target) {
        colorTarget = target;
        syncColorUi();
    }

    private boolean isTargetCustom() {
        return colorTarget == TARGET_BG ? customBg : customTextColor;
    }

    private void setTargetCustom(boolean custom) {
        if (colorTarget == TARGET_BG) {
            customBg = custom;
        } else {
            customTextColor = custom;
        }
    }

    private int targetRgb() {
        return colorTarget == TARGET_BG ? bgRgb : textRgb;
    }

    private void setTargetRgb(int rgb) {
        if (colorTarget == TARGET_BG) {
            bgRgb = rgb;
        } else {
            textRgb = rgb;
        }
    }

    private int targetAlpha() {
        return colorTarget == TARGET_BG ? bgAlpha : textAlpha;
    }

    private void setTargetAlpha(int alpha) {
        if (colorTarget == TARGET_BG) {
            bgAlpha = alpha;
        } else {
            textAlpha = alpha;
        }
    }

    /** 当前编辑对象的实际颜色（ARGB）。 */
    private int targetArgb() {
        if (!isTargetCustom()) {
            return colorTarget == TARGET_BG
                    ? getColor(R.color.widget_bg)
                    : getColor(R.color.widget_text_primary);
        }
        return (targetAlpha() << 24) | (targetRgb() & 0xFFFFFF);
    }

    private void onColorPicked() {
        if (syncing) {
            return;
        }
        setTargetCustom(true);
        setTargetRgb(colorPicker.getColor() & 0xFFFFFF);
        syncing = true;
        themeColorCheck.setChecked(false);
        syncing = false;
        updateColorPreview();
        refreshPreview();
    }

    private void syncColorUi() {
        syncing = true;
        int reference = isTargetCustom() ? (0xFF000000 | targetRgb())
                : (colorTarget == TARGET_BG
                        ? getColor(R.color.widget_bg)
                        : getColor(R.color.widget_text_primary));
        colorPicker.setColor(reference);
        float[] hsv = new float[3];
        Color.colorToHSV(reference, hsv);
        hueSlider.setProgress(Math.round(hsv[0]));
        themeColorCheck.setChecked(!isTargetCustom());
        alphaSlider.setProgress(alphaToProgress(targetAlpha()));
        targetBg.setChecked(colorTarget == TARGET_BG);
        targetText.setChecked(colorTarget == TARGET_TEXT);
        colorLabel.setText(colorTarget == TARGET_BG
                ? R.string.label_bg_color : R.string.label_text_color);
        syncing = false;
        updateColorPreview();
        updateAlphaLabel();
    }

    private void updateColorPreview() {
        int argb = targetArgb();
        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.OVAL);
        swatch.setColor(argb);
        swatch.setStroke(dp(1), 0x33000000);
        colorPreview.setBackground(swatch);

        if (!isTargetCustom()) {
            colorHex.setText(getString(R.string.bg_theme_color));
        } else {
            colorHex.setText(String.format("#%06X · %d%%", targetRgb() & 0xFFFFFF,
                    alphaToProgress(targetAlpha())));
        }
    }

    private void updateAlphaLabel() {
        alphaLabel.setText(getString(R.string.alpha_percent, alphaToProgress(targetAlpha())));
    }

    private int currentBgArgb() {
        if (!customBg) {
            return getColor(R.color.widget_bg);
        }
        return (bgAlpha << 24) | (bgRgb & 0xFFFFFF);
    }

    private int currentTextArgb() {
        if (!customTextColor) {
            return getColor(R.color.widget_text_primary);
        }
        return (textAlpha << 24) | (textRgb & 0xFFFFFF);
    }

    private static int alphaToProgress(int alpha) {
        return Math.round(alpha * 100f / 255f);
    }

    // ---------------------------------------------------------------- 尺寸

    private void setupSizeUi() {
        sizeFill.setOnClickListener(view -> {
            sizeMode = WidgetData.SIZE_FILL;
            syncSizeUi();
            onBoxChanged();
        });
        sizeCustom.setOnClickListener(view -> {
            sizeMode = WidgetData.SIZE_CUSTOM;
            syncSizeUi();
            onBoxChanged();
        });
        sizeImage.setOnClickListener(view -> {
            sizeMode = WidgetData.SIZE_IMAGE;
            syncSizeUi();
            onBoxChanged();
        });

        int range = WidgetUi.SIZE_MAX_PERCENT - WidgetUi.SIZE_MIN_PERCENT;
        widthSlider.setMax(range);
        heightSlider.setMax(range);
        widthSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (syncing) {
                    return;
                }
                sizePercentW = WidgetUi.clampPercent(progress + WidgetUi.SIZE_MIN_PERCENT);
                if (squareLock.isChecked()) {
                    sizePercentH = sizePercentW;
                }
                syncSizeUi();
                onBoxChanged();
            }
        });
        heightSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (syncing) {
                    return;
                }
                sizePercentH = WidgetUi.clampPercent(progress + WidgetUi.SIZE_MIN_PERCENT);
                if (squareLock.isChecked()) {
                    sizePercentW = sizePercentH;
                }
                syncSizeUi();
                onBoxChanged();
            }
        });
        squareLock.setOnCheckedChangeListener((buttonView, checked) -> {
            if (syncing) {
                return;
            }
            if (checked) {
                sizePercentH = sizePercentW;
                syncSizeUi();
                onBoxChanged();
            }
        });
    }

    /** 圆角可调范围（dp）。 */
    private void setupRadiusUi() {
        int range = Math.round(WidgetUi.MAX_RADIUS_DP - WidgetUi.MIN_RADIUS_DP);
        radiusSlider.setMax(range);
        radiusSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (syncing) {
                    return;
                }
                cornerRadiusDp = Math.round(WidgetUi.MIN_RADIUS_DP) + progress;
                updateRadiusLabel();
                rebuildPreviewBitmap();
                refreshPreview();
            }
        });
    }

    private void syncRadiusUi() {
        radiusSlider.setProgress(cornerRadiusDp - Math.round(WidgetUi.MIN_RADIUS_DP));
        updateRadiusLabel();
    }

    private void updateRadiusLabel() {
        radiusLabel.setText(getString(R.string.size_dp, cornerRadiusDp));
    }

    private void syncSizeUi() {
        boolean custom = sizeMode == WidgetData.SIZE_CUSTOM;
        boolean image = sizeMode == WidgetData.SIZE_IMAGE;
        syncing = true;
        sizeFill.setChecked(sizeMode == WidgetData.SIZE_FILL);
        sizeCustom.setChecked(custom);
        sizeImage.setChecked(image);
        sizeImage.setEnabled(hasImageNow());

        widthSlider.setProgress(WidgetUi.clampPercent(sizePercentW) - WidgetUi.SIZE_MIN_PERCENT);
        heightSlider.setProgress(WidgetUi.clampPercent(sizePercentH) - WidgetUi.SIZE_MIN_PERCENT);
        widthSlider.setEnabled(custom);
        heightSlider.setEnabled(custom);
        squareLock.setEnabled(custom);
        squareLock.setChecked(custom && sizePercentW == sizePercentH);
        syncing = false;

        int[] box = currentBoxDp();
        if (image && imageAspect() > 0f) {
            // 比例跟随图片时，宽高是算出来的，直接显示实际 dp 更直观
            widthLabel.setText(getString(R.string.size_dp, box[0]));
            heightLabel.setText(getString(R.string.size_dp, box[1]));
        } else {
            widthLabel.setText(getString(R.string.size_percent, sizePercentW));
            heightLabel.setText(getString(R.string.size_percent, sizePercentH));
        }
        findViewById(R.id.row_size_w).setAlpha(custom || image ? 1f : 0.4f);
        findViewById(R.id.row_size_h).setAlpha(custom || image ? 1f : 0.4f);
        widthSlider.setContentDescription(getString(R.string.size_dp, box[0]));
    }

    /** 现在有没有图片可用（已选的或已保存的）。 */
    private boolean hasImageNow() {
        return sourceBitmap != null || hadImage;
    }

    /** 原图宽高比（宽 / 高）；没有图片时是 0。 */
    private float imageAspect() {
        if (sourceBitmap != null && sourceBitmap.getHeight() > 0) {
            return (float) sourceBitmap.getWidth() / sourceBitmap.getHeight();
        }
        return 0f;
    }

    private void onBoxChanged() {
        applyPreviewSize();
        int[] box = currentBoxDp();
        cropView.setFrameAspect(box[0] / (float) box[1]);
        rebuildPreviewBitmap();
        refreshPreview();
    }

    /** 桌面当前给的格子尺寸（dp）。 */
    private int[] currentCellDp() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        return WidgetUi.widgetSizeDp(manager, widgetId);
    }

    /** 卡片实际尺寸（dp）。 */
    private int[] currentBoxDp() {
        WidgetData temp = new WidgetData();
        temp.sizeMode = sizeMode;
        temp.sizePercentW = sizePercentW;
        temp.sizePercentH = sizePercentH;
        temp.imageAspect = imageAspect();
        int[] cell = currentCellDp();
        return WidgetUi.boxSizeDp(temp, cell[0], cell[1]);
    }

    // ---------------------------------------------------------------- 文字

    private void setupTextUi() {
        textSizeSlider.setMax(150);
        textSizeSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textScalePercent = progress + 50;
                updateTextSizeLabel();
                applyPreviewTextStyle();
            }
        });

        offsetXSlider.setMax(100);
        offsetYSlider.setMax(100);
        offsetXSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textOffsetXPercent = progress - 50;
                updateOffsetLabels();
                applyPreviewTextStyle();
            }
        });
        offsetYSlider.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                textOffsetYPercent = progress - 50;
                updateOffsetLabels();
                applyPreviewTextStyle();
            }
        });

        countdownOption.setOnClickListener(view -> refreshDateUi());
        countUpOption.setOnClickListener(view -> refreshDateUi());
        vTop.setOnClickListener(view -> {
            textVPos = WidgetData.VPOS_TOP;
            applyPreviewTextStyle();
        });
        vCenter.setOnClickListener(view -> {
            textVPos = WidgetData.VPOS_CENTER;
            applyPreviewTextStyle();
        });
        vBottom.setOnClickListener(view -> {
            textVPos = WidgetData.VPOS_BOTTOM;
            applyPreviewTextStyle();
        });
        alignLeft.setOnClickListener(view -> {
            textAlign = WidgetData.ALIGN_LEFT;
            applyPreviewTextStyle();
        });
        alignCenter.setOnClickListener(view -> {
            textAlign = WidgetData.ALIGN_CENTER;
            applyPreviewTextStyle();
        });
        alignRight.setOnClickListener(view -> {
            textAlign = WidgetData.ALIGN_RIGHT;
            applyPreviewTextStyle();
        });

        titleInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshPreview();
            }
        });

        dateButton.setOnClickListener(view -> pickDate());
        clearButton.setOnClickListener(view -> onClear());
        findViewById(R.id.btn_cancel).setOnClickListener(view -> finish());
        findViewById(R.id.btn_save).setOnClickListener(view -> onSave());
    }

    private void syncTextUi() {
        vTop.setChecked(textVPos == WidgetData.VPOS_TOP);
        vCenter.setChecked(textVPos == WidgetData.VPOS_CENTER);
        vBottom.setChecked(textVPos == WidgetData.VPOS_BOTTOM);
        alignLeft.setChecked(textAlign == WidgetData.ALIGN_LEFT);
        alignCenter.setChecked(textAlign == WidgetData.ALIGN_CENTER);
        alignRight.setChecked(textAlign == WidgetData.ALIGN_RIGHT);
        textSizeSlider.setProgress(textScalePercent - 50);
        offsetXSlider.setProgress(textOffsetXPercent + 50);
        offsetYSlider.setProgress(textOffsetYPercent + 50);
        updateOffsetLabels();
    }

    private void updateOffsetLabels() {
        offsetXLabel.setText(getString(R.string.offset_percent, textOffsetXPercent));
        offsetYLabel.setText(getString(R.string.offset_percent, textOffsetYPercent));
    }

    private void updateTextSizeLabel() {
        textSizeLabel.setText(getString(R.string.size_percent, textScalePercent));
    }

    private int currentMode() {
        return countUpOption.isChecked() ? WidgetData.MODE_COUNT_UP : WidgetData.MODE_COUNTDOWN;
    }

    private void refreshDateUi() {
        dateLabel.setText(currentMode() == WidgetData.MODE_COUNT_UP
                ? R.string.label_date_count_up
                : R.string.label_date_countdown);
        dateButton.setText(DayMath.format(selectedEpochDay));
        refreshPreview();
    }

    private void pickDate() {
        int[] ymd = DayMath.ymd(selectedEpochDay);
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            selectedEpochDay = DayMath.epochDayOf(year, month + 1, dayOfMonth);
            refreshDateUi();
        }, ymd[0], ymd[1] - 1, ymd[2]);
        dialog.show();
    }

    // ---------------------------------------------------------------- 图片

    private void setupImageUi() {
        findViewById(R.id.btn_pick_image).setOnClickListener(view -> pickImage());
        findViewById(R.id.btn_remove_image).setOnClickListener(view -> removeImage());
        cropButton.setOnClickListener(view -> toggleCrop());
        findViewById(R.id.btn_crop_reset).setOnClickListener(view -> cropView.resetCrop());
        findViewById(R.id.btn_zoom_in).setOnClickListener(view -> cropView.zoomBy(1.25f));
        findViewById(R.id.btn_zoom_out).setOnClickListener(view -> cropView.zoomBy(0.8f));
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        pickingImage = true;
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("image/*");
            try {
                startActivityForResult(fallback, REQ_PICK_IMAGE);
            } catch (ActivityNotFoundException ignored) {
                pickingImage = false;
                Toast.makeText(this, R.string.toast_image_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void removeImage() {
        sourceBitmap = null;
        previewBitmap = null;
        imageState = IMAGE_REMOVE;
        cropVisible = false;
        cropView.setBitmap(null);
        refreshImageStatus();
    }

    private void toggleCrop() {
        if (sourceBitmap == null) {
            return;
        }
        cropVisible = !cropVisible;
        refreshCropVisibility();
    }

    private void refreshCropVisibility() {
        int visibility = cropVisible && sourceBitmap != null ? View.VISIBLE : View.GONE;
        cropView.setVisibility(visibility);
        cropActions.setVisibility(visibility);
        cropButton.setText(cropVisible ? R.string.action_crop_done : R.string.action_crop);
        cropButton.setEnabled(sourceBitmap != null);
        if (visibility == View.VISIBLE) {
            // 等这次布局结束再初始化，避免控件还是 0x0 时算错比例
            cropView.post(cropView::ensureInitialized);
        }
    }

    private void onCropChanged() {
        rebuildPreviewBitmap();
        refreshPreview();
    }

    /** 预览图也按"目标分辨率 + 双三次插值"渲染，所见即所得。 */
    private void rebuildPreviewBitmap() {
        if (sourceBitmap == null) {
            previewBitmap = null;
            return;
        }
        float[] c = cropView.getCropRect();
        previewBitmap = ImageStore.renderForTarget(sourceBitmap, c[0], c[1], c[2], c[3],
                Math.max(1, previewWidthPx), Math.max(1, previewHeightPx),
                dp(cornerRadiusDp * previewScale));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到前台说明"出去选图"这一趟已经结束（onActivityResult 先于 onResume）
        pickingImage = false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE) {
            return;
        }
        pickingImage = false;
        try {
            handlePickedImage(resultCode, data);
        } catch (RuntimeException e) {
            // 各种系统相册/文件管理器的实现千奇百怪，兜住异常，别让配置页崩掉
            Log.w(TAG, "处理所选图片失败", e);
            Toast.makeText(this, R.string.toast_image_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void handlePickedImage(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        Bitmap picked = null;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input != null) {
                picked = ImageStore.importFrom(this, input, ImageStore.storeMaxPx());
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "读取图片失败", e);
            picked = null;
        }
        if (picked == null) {
            Toast.makeText(this, R.string.toast_image_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        sourceBitmap = picked;
        previewBitmap = picked;
        imageState = IMAGE_NEW;
        cropView.setBitmap(picked);
        cropVisible = true; // 选完图直接展开裁剪，方便马上调整
        // 卡片比例可能"跟随图片"，选了新图尺寸要跟着变；裁剪框也跟着重算
        onBoxChanged();
        refreshImageStatus();
    }

    private void refreshImageStatus() {
        int text;
        switch (imageState) {
            case IMAGE_NEW:
                text = R.string.image_picked;
                break;
            case IMAGE_REMOVE:
                text = R.string.image_will_remove;
                break;
            default:
                text = hadImage ? R.string.image_existing : R.string.image_none;
                break;
        }
        imageStatus.setText(text);
        sizeImage.setEnabled(hasImageNow());
        refreshCropVisibility();
        refreshPreview();
    }

    // ---------------------------------------------------------------- 预览

    /** 预览卡片和真实卡片同比例、同字号比例。 */
    private void applyPreviewSize() {
        int[] box = currentBoxDp();
        float density = getResources().getDisplayMetrics().density;
        float widthPx = Math.max(1f, box[0] * density);
        float heightPx = Math.max(1f, box[1] * density);

        float maxPx = dp(PREVIEW_MAX_DP);
        previewScale = Math.min(1f, maxPx / Math.max(widthPx, heightPx));
        // 下限取小一点：比例很扁/很窄的卡片也能保持正确的长宽比
        int previewW = Math.max(dp(16), Math.round(widthPx * previewScale));
        int previewH = Math.max(dp(16), Math.round(heightPx * previewScale));
        previewWidthPx = previewW;
        previewHeightPx = previewH;

        ViewGroup.LayoutParams params = previewCard.getLayoutParams();
        if (params.width != previewW || params.height != previewH) {
            params.width = previewW;
            params.height = previewH;
            previewCard.setLayoutParams(params);
        }
    }

    private void refreshPreview() {
        int argb = currentBgArgb();
        GradientDrawable card = new GradientDrawable();
        card.setShape(GradientDrawable.RECTANGLE);
        card.setCornerRadius(dp(cornerRadiusDp * previewScale));
        card.setColor(argb);
        card.setStroke(Math.max(1, dp(previewScale)),
                CardBackground.isLight(argb) ? 0x1F000000 : 0x33FFFFFF);
        previewCard.setBackground(card);

        previewImage.setImageBitmap(previewBitmap);
        applyPreviewTextStyle();
        applyPreviewTextContent();
    }

    private void applyPreviewTextStyle() {
        float scale = previewScale * textScalePercent / 100f;
        int gravity = WidgetUi.textAlignGravity(textAlign);
        int color = customTextColor ? currentTextArgb() : 0;
        previewText.setGravity(WidgetUi.textBlockGravity(textVPos));
        previewNumberRow.setGravity(WidgetUi.textAlignGravity(textAlign));
        previewUnit.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                Math.max(5f, WidgetUi.BASE_UNIT_SP * scale));
        previewUnit.setTextColor(customTextColor ? color : getColor(R.color.widget_text_primary));
        // 微调偏移：和真实组件一样按卡片尺寸取百分比，预览按预览尺寸等比
        previewText.setTranslationX(textOffsetXPercent / 100f * previewWidthPx);
        previewText.setTranslationY(textOffsetYPercent / 100f * previewHeightPx);
        setPreviewTextStyle(previewTitle, WidgetUi.BASE_TITLE_SP, scale, gravity,
                customTextColor ? color : getColor(R.color.widget_text_secondary));
        setPreviewTextStyle(previewNumber, WidgetUi.BASE_NUMBER_SP, scale, gravity,
                customTextColor ? color : getColor(R.color.widget_text_primary));
        setPreviewTextStyle(previewDate, WidgetUi.BASE_DATE_SP, scale, gravity,
                customTextColor ? color : getColor(R.color.widget_text_tertiary));
    }

    private void setPreviewTextStyle(TextView view, float baseSp, float scale,
                                     int gravity, int color) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(5f, baseSp * scale));
        view.setGravity(gravity);
        view.setTextColor(color);
    }

    private void applyPreviewTextContent() {
        String title = titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            title = getString(R.string.default_title);
        }
        WidgetData data = new WidgetData();
        data.title = title;
        data.mode = currentMode();
        data.dateEpochDay = selectedEpochDay;

        WidgetText text = WidgetText.of(this, data, DayMath.todayEpochDay());
        previewTitle.setText(title);
        previewNumber.setText(text.number);
        previewUnit.setText(text.unit);
        previewDate.setText(text.dateLine);
    }

    // ---------------------------------------------------------------- 保存

    private void onSave() {
        String title = titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            title = getString(R.string.default_title);
        }

        WidgetData data = new WidgetData();
        data.title = title;
        data.dateEpochDay = selectedEpochDay;
        data.mode = currentMode();
        data.customBg = customBg;
        data.bgColor = currentBgArgb();
        data.customTextColor = customTextColor;
        data.textColor = currentTextArgb();
        data.textVPos = textVPos;
        data.textAlign = textAlign;
        data.textScalePercent = textScalePercent;
        data.textOffsetXPercent = textOffsetXPercent;
        data.textOffsetYPercent = textOffsetYPercent;
        data.sizeMode = sizeMode;
        data.sizePercentW = WidgetUi.clampPercent(sizePercentW);
        data.sizePercentH = WidgetUi.clampPercent(sizePercentH);
        data.imageAspect = imageAspect();
        data.cornerRadiusDp = cornerRadiusDp;

        float[] crop = cropView.getCropRect();
        boolean hasImage;
        if (imageState == IMAGE_REMOVE || sourceBitmap == null) {
            ImageStore.delete(this, widgetId);
            hasImage = false;
            crop = new float[] { 0f, 0f, 1f, 1f };
        } else {
            // 原图 + 成品各存一份：原图供以后重新裁剪，成品按目标分辨率双三次渲染
            ImageStore.saveSource(this, widgetId, sourceBitmap);
            int[] box = currentBoxDp();
            int[] target = WidgetUi.targetPixels(this, box);
            hasImage = ImageStore.saveDisplay(this, widgetId,
                    crop[0], crop[1], crop[2], crop[3], target[0], target[1],
                    cornerRadiusDp * getResources().getDisplayMetrics().density);
            if (hasImage) {
                data.renderedBoxWidthDp = box[0];
                data.renderedBoxHeightDp = box[1];
                data.renderRevision = ImageStore.RENDER_REVISION;
            }
        }
        data.hasImage = hasImage;
        if (hasImage) {
            data.cropLeft = crop[0];
            data.cropTop = crop[1];
            data.cropRight = crop[2];
            data.cropBottom = crop[3];
        }

        WidgetStore.save(this, widgetId, data);
        WidgetUi.updateWidget(this, widgetId);
        MidnightAlarm.schedule(this);

        setResult(RESULT_OK, resultIntent());
        Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void onClear() {
        WidgetStore.delete(this, widgetId);
        ImageStore.delete(this, widgetId);
        WidgetUi.updateWidget(this, widgetId);
        Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show();
        finish();
    }

    // ---------------------------------------------------------------- 杂项

    private Intent resultIntent() {
        Intent intent = new Intent();
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return intent;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** SeekBar 监听器的空实现，省得每次写三个方法。 */
    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
