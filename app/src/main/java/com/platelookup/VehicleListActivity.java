package com.platelookup;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 车辆库：增删改查 + CSV 批量导入，数据全部落在本机 SQLite。 */
public class VehicleListActivity extends AppCompatActivity {

    private static final int REQ_OPEN_DOC = 301;

    private final List<Vehicle> items = new ArrayList<>();

    private ListView listView;
    private TextView tvEmpty;
    private TextView tvCount;
    private EditText etSearch;
    private VehicleAdapter adapter;

    private VehicleStore store;
    private ExecutorService bgExecutor;
    private String keyword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vehicle_list);

        listView = findViewById(R.id.list);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvCount = findViewById(R.id.tvCount);
        etSearch = findViewById(R.id.etSearch);
        ImageButton btnBack = findViewById(R.id.btnBack);
        ImageButton btnAdd = findViewById(R.id.btnAdd);
        ImageButton btnImport = findViewById(R.id.btnImport);

        store = VehicleStore.get(this);
        bgExecutor = Executors.newSingleThreadExecutor();

        adapter = new VehicleAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) ->
                showEditDialog(adapter.getItem(position)));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(adapter.getItem(position));
            return true;
        });

        btnBack.setOnClickListener(v -> finish());
        btnAdd.setOnClickListener(v -> showEditDialog(null));
        btnImport.setOnClickListener(v -> pickCsv());
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                keyword = s.toString().trim();
                reload();
            }
        });

        reload();
    }

    private void reload() {
        bgExecutor.execute(() -> {
            final List<Vehicle> data = store.query(keyword);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                items.clear();
                items.addAll(data);
                adapter.notifyDataSetChanged();
                tvCount.setText(getString(R.string.count_fmt, data.size()));
                if (data.isEmpty()) {
                    tvEmpty.setText(keyword.isEmpty() ? R.string.empty_library : R.string.empty_search);
                    tvEmpty.setVisibility(View.VISIBLE);
                } else {
                    tvEmpty.setVisibility(View.GONE);
                }
            });
        });
    }

    // ------------------------------------------------------------ 新增 / 编辑

    private void showEditDialog(Vehicle existing) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_vehicle, null, false);
        EditText etPlate = content.findViewById(R.id.etPlate);
        EditText etOwner = content.findViewById(R.id.etOwner);
        EditText etPhone = content.findViewById(R.id.etPhone);

        if (existing != null) {
            etPlate.setText(existing.plate);
            etOwner.setText(existing.owner);
            etPhone.setText(existing.phone);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(existing == null ? R.string.dialog_add : R.string.dialog_edit)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null);
        if (existing != null) {
            builder.setNeutralButton(R.string.delete, (d, w) -> confirmDelete(existing));
        }
        final AlertDialog dialog = builder.create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String plate = PlateParser.fromUserInput(etPlate.getText().toString());
            String owner = etOwner.getText().toString().trim();
            // 手机号允许为空：历史名单里存在只登记姓名的车主
            String phone = CsvImporter.cleanPhone(etPhone.getText().toString());
            if (!PlateParser.isValid(plate)) {
                etPlate.setError(getString(R.string.manual_invalid));
                return;
            }
            if (owner.isEmpty()) {
                etOwner.setError(getString(R.string.field_owner));
                return;
            }
            final Vehicle vehicle = new Vehicle(plate, owner, phone);
            bgExecutor.execute(() -> {
                // 改了车牌号时，删掉旧记录，避免残留
                if (existing != null && !existing.plate.equals(plate)) {
                    store.delete(existing.plate);
                }
                store.upsert(vehicle);
                runOnUiThread(() -> {
                    dialog.dismiss();
                    reload();
                });
            });
        });
    }

    private void confirmDelete(Vehicle v) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_confirm, v.plate))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> bgExecutor.execute(() -> {
                    store.delete(v.plate);
                    runOnUiThread(this::reload);
                }))
                .show();
    }

    // ------------------------------------------------------------ CSV 导入

    private void pickCsv() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_OPEN_DOC);
        } catch (Exception e) {
            Toast.makeText(this, R.string.import_empty, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN_DOC || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        bgExecutor.execute(() -> {
            String text;
            try {
                text = readText(uri);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, R.string.import_empty,
                        Toast.LENGTH_LONG).show());
                return;
            }
            final CsvImporter.Result parsed = CsvImporter.parse(text);
            if (parsed.items.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, R.string.import_empty,
                        Toast.LENGTH_LONG).show());
                return;
            }
            final VehicleStore.ImportStats stats = store.upsertAllCounted(parsed.items);
            runOnUiThread(() -> {
                StringBuilder msg = new StringBuilder(
                        getString(R.string.import_summary, parsed.items.size(),
                                stats.inserted, stats.updated));
                if (parsed.noPhoneCount > 0) {
                    msg.append(getString(R.string.import_no_phone_note, parsed.noPhoneCount));
                }
                if (parsed.skippedCount > 0) {
                    msg.append(getString(R.string.import_skipped_note, parsed.skippedCount));
                }
                Toast.makeText(this, msg.toString(), Toast.LENGTH_LONG).show();
                reload();
            });
        });
    }

    /** 读取文件并按 UTF-8 / GBK 自动解；CSV 常来自 Excel，GBK 很常见。 */
    private String readText(Uri uri) throws Exception {
        String text;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IllegalStateException("stream is null");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            byte[] bytes = bos.toByteArray();
            text = new String(bytes, StandardCharsets.UTF_8);
            if (text.indexOf('\uFFFD') >= 0) {
                try {
                    text = new String(bytes, "GBK");
                } catch (Exception ignored) {
                    // 保留 UTF-8 结果
                }
            }
        }
        return text;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bgExecutor != null) {
            bgExecutor.shutdown();
        }
    }

    // ------------------------------------------------------------ 列表适配器

    private class VehicleAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Vehicle getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_vehicle, parent, false);
                holder = new ViewHolder();
                holder.plate = convertView.findViewById(R.id.tvItemPlate);
                holder.owner = convertView.findViewById(R.id.tvItemOwner);
                holder.phone = convertView.findViewById(R.id.tvItemPhone);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            Vehicle v = getItem(position);
            holder.plate.setText(PlateParser.format(v.plate));
            holder.owner.setText(v.owner);
            if (v.hasPhone()) {
                holder.phone.setText(v.phone);
                holder.phone.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.text_secondary));
            } else {
                holder.phone.setText(R.string.no_info);
                holder.phone.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.text_tertiary));
            }
            return convertView;
        }
    }

    private static class ViewHolder {
        TextView plate;
        TextView owner;
        TextView phone;
    }
}
