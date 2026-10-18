/*
 * Copyright (C) 2026 Contributors to Amaze File Manager.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.ui.dialogs;

import static com.amaze.filemanager.filesystem.webdav.WebdavContexts.WEBDAV_URI_PREFIX;
import static com.amaze.filemanager.filesystem.webdav.WebdavContexts.WEBDAVS_URI_PREFIX;

import com.afollestad.materialdialogs.MaterialDialog;
import com.amaze.filemanager.R;
import com.amaze.filemanager.ui.provider.UtilitiesProvider;
import com.amaze.filemanager.utils.SimpleTextWatcher;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;

/**
 * Dialog for adding or editing a WebDAV connection.
 *
 * Simpler than {@link SmbConnectDialog}: just needs the server URL,
 * username and password. The URL can be http:// or https://; it will
 * be stored internally as webdav:// or webdavs://.
 */
public class WebdavConnectDialog extends DialogFragment {

  public static final String TAG = "webdavdialog";

  public static final String ARG_NAME = "name";
  public static final String ARG_PATH = "path";
  public static final String ARG_EDIT = "edit";

  private UtilitiesProvider utilsProvider;
  private WebdavConnectionListener webdavConnectionListener;

  public interface WebdavConnectionListener {
    void onWebdavConnection(String name, String path, boolean edit);
  }

  public void setWebdavConnectionListener(WebdavConnectionListener listener) {
    this.webdavConnectionListener = listener;
  }

  @NonNull
  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    // Auto-wire listener from hosting Activity, same pattern as SmbConnectDialog
    if (getActivity() instanceof WebdavConnectionListener && webdavConnectionListener == null) {
      webdavConnectionListener = (WebdavConnectionListener) getActivity();
    }

    utilsProvider = getActivity() instanceof com.amaze.filemanager.ui.activities.MainActivity
        ? ((com.amaze.filemanager.ui.activities.MainActivity) getActivity()).getUtilsProvider()
        : null;

    final boolean edit = getArguments() != null && getArguments().getBoolean(ARG_EDIT, false);
    final String oldName = getArguments() != null ? getArguments().getString(ARG_NAME) : null;
    final String oldPath = getArguments() != null ? getArguments().getString(ARG_PATH) : null;

    View view = getActivity().getLayoutInflater().inflate(R.layout.dialog_webdav_connect, null);
    final EditText nameEdit = view.findViewById(R.id.webdav_name);
    final EditText urlEdit = view.findViewById(R.id.webdav_url);
    final EditText userEdit = view.findViewById(R.id.webdav_username);
    final EditText passEdit = view.findViewById(R.id.webdav_password);

    if (edit && oldName != null) {
      nameEdit.setText(oldName);
    }
    if (edit && oldPath != null) {
      // Convert webdav(s):// back to http(s):// for display
      String displayUrl = oldPath;
      if (displayUrl.startsWith(WEBDAV_URI_PREFIX)) {
        displayUrl = "http://" + displayUrl.substring(WEBDAV_URI_PREFIX.length());
      } else if (displayUrl.startsWith(WEBDAVS_URI_PREFIX)) {
        displayUrl = "https://" + displayUrl.substring(WEBDAVS_URI_PREFIX.length());
      }
      // Strip embedded credentials for display; user re-enters them
      try {
        android.net.Uri uri = android.net.Uri.parse(displayUrl);
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(host);
        if (port != -1) sb.append(':').append(port);
        if (path != null) sb.append(path);
        displayUrl = sb.toString();
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
          String[] parts = userInfo.split(":", 2);
          userEdit.setText(parts[0]);
          // password stays encrypted in URI; ask user to re-enter
        }
      } catch (Exception e) {
        // fall back to raw display
      }
      urlEdit.setText(displayUrl);
    }

    MaterialDialog dialog =
        new MaterialDialog.Builder(getActivity())
            .title(edit ? R.string.edit_webdav_connection : R.string.add_webdav_connection)
            .customView(view, true)
            .positiveText(R.string.ok)
            .negativeText(R.string.cancel)
            .onPositive(
                (d, which) -> {
                  String name = nameEdit.getText().toString().trim();
                  String url = urlEdit.getText().toString().trim();
                  String user = userEdit.getText().toString().trim();
                  String pass = passEdit.getText().toString();

                  if (TextUtils.isEmpty(name)) {
                    nameEdit.setError(getString(R.string.field_empty));
                    return;
                  }
                  if (TextUtils.isEmpty(url)) {
                    urlEdit.setError(getString(R.string.field_empty));
                    return;
                  }
                  // Normalize URL: ensure scheme, convert to webdav(s)://
                  if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "http://" + url;
                  }
                  String webdavUri;
                  if (url.startsWith("https://")) {
                    webdavUri = WEBDAVS_URI_PREFIX + url.substring("https://".length());
                  } else {
                    webdavUri = WEBDAV_URI_PREFIX + url.substring("http://".length());
                  }
                  // Embed credentials in URI (password encrypted by caller)
                  if (!TextUtils.isEmpty(user)) {
                    try {
                      String encPass =
                          com.amaze.filemanager.utils.PasswordUtil.INSTANCE.encryptPassword(
                              getActivity(), pass, android.util.Base64.URL_SAFE);
                      android.net.Uri uri = android.net.Uri.parse(webdavUri);
                      String authority =
                          user + ":" + encPass + "@" + uri.getAuthority();
                      // strip any old userinfo from authority
                      String host = uri.getHost();
                      int port = uri.getPort();
                      StringBuilder auth = new StringBuilder();
                      auth.append(user).append(':').append(encPass).append('@').append(host);
                      if (port != -1) auth.append(':').append(port);
                      webdavUri =
                          uri.getScheme() + "://" + auth + (uri.getPath() != null ? uri.getPath() : "");
                    } catch (Exception e) {
                      // fall back to no credentials in URI
                    }
                  }
                  // Ensure trailing slash for collections
                  if (!webdavUri.endsWith("/")) {
                    webdavUri += "/";
                  }
                  if (webdavConnectionListener != null) {
                    webdavConnectionListener.onWebdavConnection(name, webdavUri, edit);
                  }
                })
            .build();

    // Enable OK only when name and URL are non-empty
    View positive = dialog.getActionButton(com.afollestad.materialdialogs.DialogAction.POSITIVE);
    SimpleTextWatcher watcher =
        new SimpleTextWatcher() {
          @Override
          public void afterTextChanged(Editable s) {
            positive.setEnabled(
                !TextUtils.isEmpty(nameEdit.getText().toString().trim())
                    && !TextUtils.isEmpty(urlEdit.getText().toString().trim()));
          }
        };
    nameEdit.addTextChangedListener(watcher);
    urlEdit.addTextChangedListener(watcher);
    positive.setEnabled(false);

    return dialog;
  }
}
