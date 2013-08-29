package com.yubico.yubioath.fragments;

import android.app.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.*;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.widget.*;
import com.yubico.yubioath.MainActivity;
import com.yubico.yubioath.R;
import com.yubico.yubioath.model.KeyManager;
import com.yubico.yubioath.model.YubiKeyNeo;

import java.io.IOException;
import java.util.*;

/**
 * Created with IntelliJ IDEA.
 * User: dain
 * Date: 8/26/13
 * Time: 11:08 AM
 * To change this template use File | Settings | File Templates.
 */
public class ListCodesFragment extends ListFragment implements MainActivity.OnYubiKeyNeoListener, ActionMode.Callback {
    private static final int READ_LIST = 0;
    private static final int READ_SELECTED = 1;
    private static final int DELETE_SELECTED = 2;

    private final TimeoutAnimation timeoutAnimation = new TimeoutAnimation();
    private CodeAdapter adapter;
    private ProgressBar timeoutBar;
    private ActionMode actionMode;
    private int state = READ_LIST;
    private OathCode selectedItem;
    private DialogFragment swipeDialog = new SwipeDialog();

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.list_codes_fragment, container, false);
        timeoutBar = (ProgressBar) view.findViewById(R.id.timeRemaining);
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        adapter = new CodeAdapter(new ArrayList<OathCode>());
        setListAdapter(adapter);

        getListView().setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if(actionMode != null) {
                    selectedItem = adapter.getItem(position);
                    actionMode.setTitle(selectedItem.getLabel());
                    getListView().setItemChecked(position, true);
                } else {
                    getListView().setItemChecked(position, false);
                }
            }
        });
        getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                selectedItem = adapter.getItem(position);
                if (actionMode == null) {
                    actionMode = getActivity().startActionMode(ListCodesFragment.this);
                }
                actionMode.setTitle(selectedItem.getLabel());
                getListView().setItemChecked(position, true);
                return true;
            }
        });
    }

    @Override
    public void onYubiKeyNeo(YubiKeyNeo neo) throws IOException {
        long timestamp = System.currentTimeMillis() / 1000 / 30;

        switch (state) {
            case READ_LIST:
                showCodes(neo.getCodes(timestamp));
                break;
            case READ_SELECTED:
                selectedItem.setCode(neo.readHotpCode(selectedItem.getLabel()));
                adapter.notifyDataSetChanged();
                swipeDialog.dismiss();
                state = READ_LIST;
                break;
            case DELETE_SELECTED:
                neo.deleteCode(selectedItem.getLabel());
                selectedItem = null;
                swipeDialog.dismiss();
                Toast.makeText(getActivity(), R.string.deleted, Toast.LENGTH_SHORT).show();
                showCodes(neo.getCodes(timestamp));
                state = READ_LIST;
                break;
        }
    }

    public void showCodes(List<Map<String, String>> codeMap) {
        List<OathCode> codes = new ArrayList<OathCode>();
        boolean hasTimeout = false;
        for (Map<String, String> code : codeMap) {
            OathCode oathCode = new OathCode(code.get("label"), code.get("code"));
            hasTimeout = hasTimeout || !oathCode.hotp;
            codes.add(oathCode);
        }

        if(actionMode != null) {
            actionMode.finish();
        }

        adapter.setAll(codes);

        if (codes.size() == 0) {
            Toast.makeText(getActivity(), R.string.empty_list, Toast.LENGTH_LONG).show();
        }
        if(hasTimeout) {
            timeoutBar.startAnimation(timeoutAnimation);
        } else {
            timeoutAnimation.cancel();
            timeoutBar.setProgress(0);
        }
    }

    @Override
    public void onPasswordMissing(KeyManager keyManager, byte[] id, boolean missing) {
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        Fragment prev = getFragmentManager().findFragmentByTag("dialog");
        if (prev != null) {
            ft.remove(prev);
        }
        DialogFragment dialog = RequirePasswordDialog.newInstance(keyManager, id, missing);
        dialog.show(ft, "dialog");
    }

    private void readHotp(OathCode code) {
        if(actionMode != null) {
            actionMode.finish();
        }
        selectedItem = code;
        state = READ_SELECTED;
        swipeDialog.show(getFragmentManager(), "dialog");
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        switch (item.getItemId()) {
            case R.id.delete:
                state = DELETE_SELECTED;
                swipeDialog.show(getFragmentManager(), "dialog");
                break;
            default:
                return false;
        }
        actionMode.finish();
        return true;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(R.menu.code_select_actions, menu);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        actionMode = null;
        getListView().setItemChecked(getListView().getCheckedItemPosition(), false);
    }

    private class CodeAdapter extends ArrayAdapter<OathCode> {
        private final Comparator<OathCode> comparator = new Comparator<OathCode>() {
            @Override
            public int compare(OathCode lhs, OathCode rhs) {
                return lhs.getLabel().toLowerCase().compareTo(rhs.getLabel().toLowerCase());
            }
        };
        private boolean expired = false;

        public CodeAdapter(List<OathCode> codes) {
            super(getActivity(), R.layout.oath_code_view, codes);
        }

        public void setAll(List<OathCode> codes) {
            clear();
            expired = false;
            addAll(codes);
            sort(comparator);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = getActivity().getLayoutInflater();
            OathCode code = getItem(position);

            View view = convertView != null ? convertView : inflater.inflate(R.layout.oath_code_view, null);
            getListView().setItemChecked(position, actionMode != null && selectedItem == code);
            ((ViewGroup) view).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

            TextView labelView = (TextView) view.findViewById(R.id.label);
            TextView codeView = (TextView) view.findViewById(R.id.code);
            ImageButton readButton = (ImageButton) view.findViewById(R.id.readButton);
            ImageButton copyButton = (ImageButton) view.findViewById(R.id.copyButton);

            labelView.setText(code.getLabel());
            codeView.setText(code.read ? code.getCode() : "<refresh to read>");
            boolean valid = code.hotp && code.read || !code.hotp && !expired;
            codeView.setTextColor(getResources().getColor(valid ? android.R.color.primary_text_dark : android.R.color.secondary_text_light));
            readButton.setOnClickListener(code.readAction);
            copyButton.setOnClickListener(code.copyAction);
            readButton.setVisibility(code.hotp ? View.VISIBLE : View.GONE);
            copyButton.setVisibility(code.read ? View.VISIBLE : View.GONE);

            return view;
        }
    }

    private class TimeoutAnimation extends Animation {
        public TimeoutAnimation() {
            setDuration(30000);
            setAnimationListener(new AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    adapter.expired = true;
                    adapter.notifyDataSetChanged();
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                }
            });
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            super.applyTransformation(interpolatedTime, t);
            timeoutBar.setProgress((int) ((1.0 - interpolatedTime) * 1000));
        }
    }

    public class OathCode {
        private final String label;
        private String code;
        private boolean read;
        private boolean hotp = false;
        private final View.OnClickListener readAction = new ReadAction();
        private final View.OnClickListener copyAction = new CopyAction();

        public OathCode(String label, String code) {
            this.label = label;
            this.code = code;
            read = code != null;
            hotp = code == null;
        }

        public String getLabel() {
            return label;
        }

        public void setCode(String code) {
            this.code = code;
            read = code != null;
        }

        public String getCode() {
            return code;
        }

        private class CopyAction implements View.OnClickListener {
            @Override
            public void onClick(View v) {
                if (read) {
                    ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText(label, code);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(getActivity(), R.string.copied, Toast.LENGTH_SHORT).show();
                }
            }
        }

        private class ReadAction implements View.OnClickListener {
            @Override
            public void onClick(View v) {
                readHotp(OathCode.this);
            }
        }
    }
}
