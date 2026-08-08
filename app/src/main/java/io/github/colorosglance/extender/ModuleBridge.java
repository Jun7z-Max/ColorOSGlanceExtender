package io.github.colorosglance.extender;

import android.net.Uri;

final class ModuleBridge {
    static final String PACKAGE_NAME = "io.github.colorosglance.extender";
    static final String CONTROL_AUTHORITY = PACKAGE_NAME + ".control";
    static final Uri CONTROL_URI = Uri.parse("content://" + CONTROL_AUTHORITY);

    static final String ACTION_REFRESH = PACKAGE_NAME + ".action.REFRESH_CARDS";

    static final String METHOD_GET_DISABLED = "getDisabled";
    static final String METHOD_SET_DISABLED = "setDisabled";
    static final String METHOD_GET_CATALOG = "getCatalog";
    static final String METHOD_PUBLISH_CATALOG = "publishCatalog";
    static final String METHOD_REQUEST_REFRESH = "requestRefresh";

    static final String KEY_DISABLED_IDS = "disabled_ids";
    static final String KEY_CATALOG = "catalog";
    static final String KEY_UPDATED_AT = "updated_at";
    static final String KEY_REFRESH_SENT = "refresh_sent";

    private ModuleBridge() {
    }
}
