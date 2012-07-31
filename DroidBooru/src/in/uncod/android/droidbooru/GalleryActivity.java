package in.uncod.android.droidbooru;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import android.accounts.Account;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem.OnMenuItemClickListener;

public class GalleryActivity extends SherlockActivity {
    private class GalleryActionModeHandler implements ActionMode.Callback {
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            // Setup menu and mode title
            mode.setTitle(R.string.select_files_to_share);
            menu.add(R.string.share).setOnMenuItemClickListener(new OnMenuItemClickListener() {
                public boolean onMenuItemClick(com.actionbarsherlock.view.MenuItem item) {
                    if (mSelectedItems.size() > 1) {
                        // Sharing multiple links
                        Intent intent = new Intent();
                        intent.setAction(Intent.ACTION_SEND);
                        intent.putExtra(Intent.EXTRA_STREAM,
                                Uri.fromFile(mBackend.createLinkContainer(mSelectedItems)));
                        intent.setType("text/plain");
                        startActivityForResult(
                                Intent.createChooser(intent,
                                        getResources().getString(R.string.share_files_with)),
                                REQ_CODE_CHOOSE_SHARING_APP);
                    }
                    else if (mSelectedItems.size() == 1) {
                        // Sharing a single link
                        Intent intent = new Intent();
                        intent.setAction(Intent.ACTION_SEND);
                        intent.putExtra(Intent.EXTRA_TEXT, mSelectedItems.get(0).getActualUrl().toString());
                        intent.setType("text/plain");
                        startActivityForResult(
                                Intent.createChooser(intent,
                                        getResources().getString(R.string.share_files_with)),
                                REQ_CODE_CHOOSE_SHARING_APP);
                    }

                    return true;
                }
            });

            return true;
        }

        public void onDestroyActionMode(ActionMode mode) {
            mActionMode = null;
            mSelectedItems.clear();
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            // Update selected item count
            if (mSelectedItems.size() > 0) {
                mode.setSubtitle(mSelectedItems.size() + getResources().getString(R.string.items_selected));
            }
            else {
                mode.setSubtitle("");
            }

            return true;
        }

        public boolean onActionItemClicked(ActionMode mode, com.actionbarsherlock.view.MenuItem item) {
            return false;
        }
    }

    private class UpdateDisplayedFilesCallback implements FilesDownloadedCallback {
        public void onFilesDownloaded(int offset, BooruFile[] bFiles) {
            if (bFiles.length > 0) {
                int i = Math.min(mBooruFileAdapter.getCount(), offset);
                for (BooruFile file : bFiles) {
                    mBooruFileAdapter.insert(file, i); // addAll() is API level 11
                    i++;
                }

                runOnUiThread(new Runnable() {
                    public void run() {
                        mBooruFileAdapter.notifyDataSetChanged();
                    }
                });
            }
        }
    }

    private static final String TAG = "GalleryActivity";

    /**
     * Request code for choosing a file to upload
     */
    private static final int REQ_CODE_CHOOSE_FILE_UPLOAD = 0;

    /**
     * Request code for choosing which app to share files with
     */
    private static final int REQ_CODE_CHOOSE_SHARING_APP = 1;

    private Backend mBackend;

    private GridView mGridView;
    private ArrayAdapter<BooruFile> mBooruFileAdapter;

    private Account mAccount;
    private Intent mLaunchIntent;
    private boolean mDownloadWhileScrolling;

    private Handler mUiHandler;
    private ActionMode mActionMode;
    private List<BooruFile> mSelectedItems = new ArrayList<BooruFile>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gallery);

        mAccount = getIntent().getExtras()
                .getParcelable(getResources().getString(R.string.pref_account_name));

        mBackend = Backend.getInstance();
        mBackend.connect(new BackendConnectedCallback() {
            public void onBackendConnected(boolean error) {
                if (error) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(GalleryActivity.this, R.string.could_not_connect,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
                else {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            mBackend.downloadFiles(10, 0, mUiHandler, createDownloadingProgressDialog(),
                                    new UpdateDisplayedFilesCallback());
                        }
                    });
                }
            }
        });

        initializeUI();

        mUiHandler = new Handler();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Upload
        menu.add(R.string.upload).setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS)
                .setOnMenuItemClickListener(new OnMenuItemClickListener() {
                    public boolean onMenuItemClick(com.actionbarsherlock.view.MenuItem item) {
                        showFileChooser();

                        return true;
                    }
                });

        return super.onCreateOptionsMenu(menu);
    }

    private View displayThumbInView(View convertView, BooruFile booruFile) {
        String filePath = booruFile.getThumbPath();

        FrameLayout layout;

        // Determine if we can reuse the view
        if (convertView == null) {
            layout = (FrameLayout) LayoutInflater.from(this).inflate(R.layout.view_gallery_thumbnail, null);
        }
        else {
            layout = (FrameLayout) convertView;
        }

        ImageView image = (ImageView) layout.findViewById(R.id.thumbnail_image);

        // Load image
        image.setImageBitmap(BitmapFactory.decodeFile(filePath));

        return layout;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case REQ_CODE_CHOOSE_FILE_UPLOAD:
            if (resultCode == RESULT_OK) {
                // Upload chosen file
                final Uri uri = data.getData();
                Log.d(TAG, "Upload request for " + uri);

                new AsyncTask<Void, Void, File>() {
                    @Override
                    protected File doInBackground(Void... params) {
                        return mBackend.getFileForUri(uri, getContentResolver());
                    }

                    protected void onPostExecute(File uploadFile) {
                        if (uploadFile != null && uploadFile.exists()) {
                            mBackend.uploadFiles(new File[] { uploadFile }, mAccount.name,
                                    mBackend.getDefaultTags(), mUiHandler, new FilesUploadedCallback() {
                                        public void onFilesUploaded(final boolean error) {
                                            runOnUiThread(new Runnable() {
                                                public void run() {
                                                    if (!error) {
                                                        // Download and display the image that was just uploaded
                                                        mBackend.downloadFiles(1, 0, mUiHandler,
                                                                createDownloadingProgressDialog(),
                                                                new UpdateDisplayedFilesCallback());
                                                    }
                                                    else {
                                                        Toast.makeText(GalleryActivity.this,
                                                                R.string.upload_failed, Toast.LENGTH_LONG)
                                                                .show();
                                                    }
                                                }
                                            });
                                        }
                                    }, createUploadingProgressDialog(GalleryActivity.this));
                        }
                    };
                }.execute((Void) null);
            }

            break;
        case REQ_CODE_CHOOSE_SHARING_APP:
            if (resultCode == RESULT_OK) {
                mActionMode.finish();
            }

            break;
        default:
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void initializeUI() {
        mLaunchIntent = new Intent();
        mLaunchIntent.setAction(android.content.Intent.ACTION_VIEW);

        mBooruFileAdapter = new ArrayAdapter<BooruFile>(this, 0) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return displayThumbInView(convertView, mBooruFileAdapter.getItem(position));
            }
        };

        mGridView = (GridView) findViewById(R.id.images);
        mGridView.setAdapter(mBooruFileAdapter);

        mGridView.setOnItemLongClickListener(new OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (mActionMode == null) {
                    mActionMode = startActionMode(new GalleryActionModeHandler());
                }

                return false;
            }
        });

        mGridView.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (mActionMode == null) {
                    // Launch a viewer for the file
                    try {
                        BooruFile bFile = mBooruFileAdapter.getItem(position);

                        mLaunchIntent.setDataAndType(Uri.parse(bFile.getActualUrl().toString()),
                                bFile.getMimeForLaunch());

                        startActivity(mLaunchIntent);
                    }
                    catch (ActivityNotFoundException e) {
                        e.printStackTrace();

                        Toast.makeText(GalleryActivity.this,
                                "Sorry, your device can't view the original file!", Toast.LENGTH_LONG).show();
                    }
                }
                else {
                    updateSelectedFiles(position);
                }
            }
        });

        mGridView.setOnScrollListener(new OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                // Unused for now
            }

            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
                if (!mDownloadWhileScrolling && totalItemCount > 0
                        && totalItemCount - (firstVisibleItem + visibleItemCount) <= visibleItemCount * 3) {
                    mDownloadWhileScrolling = true;

                    // User only has three pages of items left to scroll through; load more
                    mBackend.downloadFiles(20, mBooruFileAdapter.getCount(), mUiHandler, null,
                            new UpdateDisplayedFilesCallback() {
                                @Override
                                public void onFilesDownloaded(int offset, BooruFile[] bFiles) {
                                    super.onFilesDownloaded(offset, bFiles);

                                    if (bFiles.length > 0) {
                                        // If we don't get anything back, assume we're at the end of the list
                                        mDownloadWhileScrolling = false;
                                    }
                                }
                            });
                }
            }
        });
    }

    private void showFileChooser() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        startActivityForResult(intent, REQ_CODE_CHOOSE_FILE_UPLOAD);
    }

    private ProgressDialog createDownloadingProgressDialog() {
        ProgressDialog dialog = new ProgressDialog(GalleryActivity.this);
        dialog.setTitle(R.string.downloading);
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setCancelable(false);

        return dialog;
    }

    public static ProgressDialog createUploadingProgressDialog(Context context) {
        ProgressDialog dialog = new ProgressDialog(context);
        dialog.setTitle(R.string.uploading);
        dialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
        dialog.setCancelable(false);

        return dialog;
    }

    private void updateSelectedFiles(int position) {
        // Add/remove in list of selected items
        BooruFile file = mBooruFileAdapter.getItem(position);

        if (mSelectedItems.contains(file)) {
            mSelectedItems.remove(file);
        }
        else {
            mSelectedItems.add(file);
        }

        mActionMode.invalidate();
    }
}