package dev.pboard.fieldzoo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * A single focused text field whose EditorInfo is chosen by intent extras, so reference
 * screenshots of any keyboard can be captured for every inputType / imeAction combination.
 *
 *   am start -S -n dev.pboard.fieldzoo/.FieldActivity --es field email
 *
 * Named presets ("field" extra): text, multiline, search, send, go, next, done, previous,
 * email, url, number, decimal, signed, phone, datetime, date, time, password, visiblepassword,
 * numberpassword, name, nosuggest, capsentences, capwords, capchars.
 * Raw overrides: --ei inputType N --ei imeOptions N.
 */
public class FieldActivity extends Activity {
    private EditText field;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout root = new FrameLayout(this);
        field = new EditText(this);
        field.setTextSize(20);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        lp.topMargin = 300;
        root.addView(field, lp);
        setContentView(root);
        configure(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); configure(i); }

    private void configure(Intent i) {
        String f = i.getStringExtra("field");
        if (f == null) f = "text";
        final int T = InputType.TYPE_CLASS_TEXT, N = InputType.TYPE_CLASS_NUMBER;
        int type = T | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        int opts = EditorInfo.IME_ACTION_UNSPECIFIED;
        switch (f) {
            case "multiline": type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE; break;
            case "search": opts = EditorInfo.IME_ACTION_SEARCH; break;
            case "send": opts = EditorInfo.IME_ACTION_SEND; break;
            case "go": opts = EditorInfo.IME_ACTION_GO; break;
            case "next": opts = EditorInfo.IME_ACTION_NEXT; break;
            case "done": opts = EditorInfo.IME_ACTION_DONE; break;
            case "previous": opts = EditorInfo.IME_ACTION_PREVIOUS; break;
            case "email": type = T | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS; opts = EditorInfo.IME_ACTION_NEXT; break;
            case "url": type = T | InputType.TYPE_TEXT_VARIATION_URI; opts = EditorInfo.IME_ACTION_GO; break;
            case "number": type = N; opts = EditorInfo.IME_ACTION_DONE; break;
            case "decimal": type = N | InputType.TYPE_NUMBER_FLAG_DECIMAL; opts = EditorInfo.IME_ACTION_DONE; break;
            case "signed": type = N | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL; opts = EditorInfo.IME_ACTION_DONE; break;
            case "phone": type = InputType.TYPE_CLASS_PHONE; opts = EditorInfo.IME_ACTION_DONE; break;
            case "datetime": type = InputType.TYPE_CLASS_DATETIME; break;
            case "date": type = InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE; break;
            case "time": type = InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME; break;
            case "password": type = T | InputType.TYPE_TEXT_VARIATION_PASSWORD; opts = EditorInfo.IME_ACTION_DONE; break;
            case "visiblepassword": type = T | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD; break;
            case "numberpassword": type = N | InputType.TYPE_NUMBER_VARIATION_PASSWORD; break;
            case "name": type = T | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS; opts = EditorInfo.IME_ACTION_NEXT; break;
            case "nosuggest": type = T | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; break;
            case "capwords": type = T | InputType.TYPE_TEXT_FLAG_CAP_WORDS; break;
            case "capchars": type = T | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS; break;
            default: break; // "text", "capsentences"
        }
        type = i.getIntExtra("inputType", type);
        opts = i.getIntExtra("imeOptions", opts);
        field.setText("");
        field.setInputType(type);
        field.setImeOptions(opts);
        field.setHint(f);
        field.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.restartInput(field);
        field.postDelayed(() -> imm.showSoftInput(field, 0), 150);
    }
}
