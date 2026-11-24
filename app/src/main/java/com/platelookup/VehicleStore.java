package com.platelookup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** 本地车辆档案库（SQLite，单文件，随应用卸载一并删除）。 */
public class VehicleStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "vehicles.db";
    private static final int DB_VERSION = 1;

    public static final String TABLE = "vehicles";
    public static final String COL_PLATE = "plate";
    public static final String COL_OWNER = "owner";
    public static final String COL_PHONE = "phone";
    public static final String COL_UPDATED = "updated_at";

    /** 识别结果与库中记录的一次匹配。matchedPlate 是库里真实存在的那串车牌。 */
    public static class Match {
        public final Vehicle vehicle;
        public final String matchedPlate;

        Match(Vehicle vehicle, String matchedPlate) {
            this.vehicle = vehicle;
            this.matchedPlate = matchedPlate;
        }
    }

    private static VehicleStore instance;

    public static synchronized VehicleStore get(Context context) {
        if (instance == null) {
            instance = new VehicleStore(context.getApplicationContext());
        }
        return instance;
    }

    private VehicleStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_PLATE + " TEXT PRIMARY KEY,"
                + COL_OWNER + " TEXT NOT NULL,"
                + COL_PHONE + " TEXT NOT NULL,"
                + COL_UPDATED + " INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 目前只有 v1，后续升级在此按版本号逐级迁移，不做破坏性重建
    }

    /** 精确查库。 */
    public Vehicle find(String plate) {
        if (plate == null || plate.isEmpty()) {
            return null;
        }
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_PLATE, COL_OWNER, COL_PHONE},
                COL_PLATE + "=?", new String[]{plate}, null, null, null);
        try {
            if (c.moveToFirst()) {
                return new Vehicle(c.getString(0), c.getString(1), c.getString(2));
            }
            return null;
        } finally {
            c.close();
        }
    }

    /** 先精确查，再按 OCR 混淆变体逐个查，命中即返回。 */
    public Match findByVariants(String plate) {
        List<String> candidates = PlateParser.variants(plate);
        for (String cand : candidates) {
            Vehicle v = find(cand);
            if (v != null) {
                return new Match(v, cand);
            }
        }
        return null;
    }

    public List<Vehicle> query(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        List<Vehicle> list = new ArrayList<>();
        Cursor c;
        if (kw.isEmpty()) {
            c = getReadableDatabase().query(TABLE,
                    new String[]{COL_PLATE, COL_OWNER, COL_PHONE},
                    null, null, null, null, COL_PLATE + " ASC");
        } else {
            String like = "%" + kw + "%";
            c = getReadableDatabase().query(TABLE,
                    new String[]{COL_PLATE, COL_OWNER, COL_PHONE},
                    COL_PLATE + " LIKE ? OR " + COL_OWNER + " LIKE ? OR " + COL_PHONE + " LIKE ?",
                    new String[]{like, like, like}, null, null, COL_PLATE + " ASC");
        }
        try {
            while (c.moveToNext()) {
                list.add(new Vehicle(c.getString(0), c.getString(1), c.getString(2)));
            }
        } finally {
            c.close();
        }
        return list;
    }

    public int count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void upsert(Vehicle v) {
        getWritableDatabase().insertWithOnConflict(TABLE, null, toValues(v),
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 批量写入，单事务提交；返回实际写入条数。 */
    public int upsertAll(List<Vehicle> items) {
        ImportStats stats = upsertAllCounted(items);
        return stats.inserted + stats.updated;
    }

    /** 批量写入并区分新增/更新，便于导入后如实反馈。 */
    public ImportStats upsertAllCounted(List<Vehicle> items) {
        ImportStats stats = new ImportStats();
        if (items == null || items.isEmpty()) {
            return stats;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Vehicle v : items) {
                boolean exists = exists(db, v.plate);
                db.insertWithOnConflict(TABLE, null, toValues(v), SQLiteDatabase.CONFLICT_REPLACE);
                if (exists) {
                    stats.updated++;
                } else {
                    stats.inserted++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return stats;
    }

    private boolean exists(SQLiteDatabase db, String plate) {
        Cursor c = db.rawQuery("SELECT 1 FROM " + TABLE + " WHERE " + COL_PLATE + "=? LIMIT 1",
                new String[]{plate});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /**
     * 按车牌片段模糊查询：手动输入只记得几位数时用。
     * 结果按「完全相同 > 前缀命中 > 包含命中」排序，最多返回 limit 条。
     */
    public List<Vehicle> searchByPlateFragment(String fragment, int limit) {
        List<Vehicle> list = new ArrayList<>();
        String f = PlateParser.normalize(fragment);
        if (f.isEmpty()) {
            return list;
        }
        Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_PLATE, COL_OWNER, COL_PHONE},
                COL_PLATE + " LIKE ?", new String[]{"%" + f + "%"},
                null, null, COL_PLATE + " ASC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                list.add(new Vehicle(c.getString(0), c.getString(1), c.getString(2)));
            }
        } finally {
            c.close();
        }
        final String key = f;
        list.sort((a, b) -> rank(a.plate, key) - rank(b.plate, key));
        return list;
    }

    private static int rank(String plate, String key) {
        if (plate.equals(key)) {
            return 0;
        }
        if (plate.startsWith(key)) {
            return 1;
        }
        if (plate.endsWith(key)) {
            return 2;
        }
        return 3;
    }

    /** 导入统计。 */
    public static class ImportStats {
        public int inserted;
        public int updated;
    }

    public boolean delete(String plate) {
        return getWritableDatabase().delete(TABLE, COL_PLATE + "=?", new String[]{plate}) > 0;
    }

    private ContentValues toValues(Vehicle v) {
        ContentValues cv = new ContentValues();
        cv.put(COL_PLATE, v.plate);
        cv.put(COL_OWNER, v.owner);
        cv.put(COL_PHONE, v.phone);
        cv.put(COL_UPDATED, System.currentTimeMillis());
        return cv;
    }

    /** 首次启动写入几条示例数据，方便立刻验证整条链路。 */
    public void seedDemoIfEmpty() {
        if (count() > 0) {
            return;
        }
        List<Vehicle> demo = new ArrayList<>();
        demo.add(new Vehicle("粤B12345", "张伟", "13800138000"));
        demo.add(new Vehicle("京A88888", "李娜", "13900139000"));
        demo.add(new Vehicle("沪C66666", "王强", "13700137000"));
        // 示例：历史上以工号代替姓名、且没登记手机号的记录（第二版可正常查询与展示）
        demo.add(new Vehicle("苏E8K3M9", "236", ""));
        upsertAll(demo);
    }
}
