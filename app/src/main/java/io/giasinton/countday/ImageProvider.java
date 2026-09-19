package io.giasinton.countday;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读地暴露某个小组件实例的成品图片，供桌面（AppWidgetHost）通过
 * {@code RemoteViews.setImageViewUri} 直接读取。
 *
 * <p>为什么不用 {@code setImageViewBitmap}：位图要塞进 RemoteViews 的 Bundle，
 * 走 Binder 传输（上限约 1MB），所以分辨率最多只能给到 384px 左右 —— 大组件上就会糊。
 * 换成 URI 之后，桌面自己去解码文件，图片能保持完整分辨率，也不占传输配额。
 *
 * <p>URI 里带上文件的修改时间（版本号），保证重新裁剪后 URI 会变 ——
 * {@code ImageView.setImageURI} 对相同 URI 会直接跳过，不换 URI 就会一直显示旧图。
 */
public class ImageProvider extends ContentProvider {

    static final String AUTHORITY = "io.giasinton.countday.image";

    static Uri uriFor(int widgetId, long version) {
        return Uri.parse("content://" + AUTHORITY + "/" + widgetId + "/" + version);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("no context");
        }
        int widgetId;
        try {
            widgetId = Integer.parseInt(uri.getPathSegments().get(0));
        } catch (RuntimeException e) {
            throw new FileNotFoundException("bad uri: " + uri);
        }
        File file = ImageStore.croppedFile(getContext(), widgetId);
        if (file == null || !file.exists()) {
            throw new FileNotFoundException("no image for widget " + widgetId);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
