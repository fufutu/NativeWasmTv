/*
 * 通用 SQLite 驱动（JNI 层）
 * -------------------------------------------------------------------
 * 这一层只做四件事：
 *   1. 打开/关闭数据库
 *   2. 加载 SQLite 扩展（路径 + 入口函数名都由 Java 传进来）
 *   3. 执行 SQL 脚本（DDL / PRAGMA / 事务）
 *   4. 执行带参数的查询，把结果按“列名 + 任意类型的值”原样回传给 Java
 *
 * 它不知道任何表名、字段名、权重或排序规则 —— 那些全在 Java 的
 * ChannelSearchEngine（业务层）里。换表、加字段、改搜索策略都不需要动这个文件。
 *
 * 只有“需要新的分词器 / 新的 SQL 函数”时才要重新编扩展本身。
 */

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sqlite3.h"

/*
 * JNI 绑定的 Java 类（下划线分隔的全限定类名），编译期可改。
 *
 * 默认 com.fufutu.tvsearch.SqliteEngine —— 与本工程的 Java 驱动类一致。
 * 别的项目换了包名时，在 CMake 里加：
 *     -DJNI_CLASS_NAME=com_example_app_Db
 * 重编即可，C 源码一行不用动（Java 侧类名/SqliteEngine 方法签名保持一致）。
 */
#ifndef JNI_CLASS_NAME
#define JNI_CLASS_NAME com_fufutu_tvsearch_SqliteEngine
#endif

#define JNI_FN2(cls, name) Java_##cls##_##name
#define JNI_FN1(cls, name) JNI_FN2(cls, name)
#define JNI_FN(name) JNI_FN1(JNI_CLASS_NAME, name)

#define ERR_LEN 512


static void throw_runtime(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls) {
        (*env)->ThrowNew(env, cls, msg ? msg : "native error");
    }
}

static void throw_sqlite(JNIEnv *env, sqlite3 *db, const char *prefix,
                         const char *extra) {
    char buf[ERR_LEN];
    const char *msg = extra && *extra ? extra : (db ? sqlite3_errmsg(db) : "");
    snprintf(buf, sizeof(buf), "%s%s", prefix ? prefix : "", msg ? msg : "");
    throw_runtime(env, buf);
}

/* 把 jstring 复制成 C 字符串；调用方负责 free */
static char *dup_jstring(JNIEnv *env, jstring js) {
    if (!js) return NULL;
    const char *s = (*env)->GetStringUTFChars(env, js, NULL);
    if (!s) return NULL;
    size_t n = strlen(s);
    char *out = (char *)malloc(n + 1);
    if (out) memcpy(out, s, n + 1);
    (*env)->ReleaseStringUTFChars(env, js, s);
    return out;
}

static sqlite3 *db_of(jlong handle) { return (sqlite3 *)(intptr_t)handle; }

/* ------------------------------------------------------------------ */
/* open / close                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL
JNI_FN(nativeOpen)(JNIEnv *env, jclass clazz, jstring dbPath) {
    char *path = dup_jstring(env, dbPath);
    if (!path) {
        throw_runtime(env, "db path is null");
        return 0;
    }

    sqlite3 *db = NULL;
    int rc = sqlite3_open_v2(path, &db,
                             SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE, NULL);
    free(path);

    if (rc != SQLITE_OK) {
        char buf[ERR_LEN];
        snprintf(buf, sizeof(buf), "sqlite3_open: %s",
                 db ? sqlite3_errmsg(db) : "out of memory");
        if (db) sqlite3_close(db);
        throw_runtime(env, buf);
        return 0;
    }

    /* 打开扩展加载开关；后面 loadExtension 才有意义 */
    rc = sqlite3_enable_load_extension(db, 1);
    if (rc != SQLITE_OK) {
        throw_sqlite(env, db, "enable_load_extension: ", NULL);
        sqlite3_close(db);
        return 0;
    }
    return (jlong)(intptr_t)db;
}

JNIEXPORT void JNICALL
JNI_FN(nativeClose)(JNIEnv *env, jclass clazz, jlong handle) {
    sqlite3 *db = db_of(handle);
    if (db) sqlite3_close(db);
}

/* ------------------------------------------------------------------ */
/* extension                                                           */
/* ------------------------------------------------------------------ */

JNIEXPORT void JNICALL
JNI_FN(nativeLoadExtension)(JNIEnv *env, jclass clazz, jlong handle, jstring soPath,
                            jstring entryPoint) {
    sqlite3 *db = db_of(handle);
    if (!db) {
        throw_runtime(env, "database not open");
        return;
    }

    char *path = dup_jstring(env, soPath);
    char *entry = dup_jstring(env, entryPoint);

    char *zErr = NULL;
    int rc = sqlite3_load_extension(db, path, entry, &zErr);
    if (rc != SQLITE_OK) {
        throw_sqlite(env, db, "load_extension: ", zErr);
        sqlite3_free(zErr);
    }
    free(path);
    free(entry);
}

/* ------------------------------------------------------------------ */
/* exec (DDL / PRAGMA / 事务脚本)                                       */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
JNI_FN(nativeExec)(JNIEnv *env, jclass clazz, jlong handle, jstring sql) {
    sqlite3 *db = db_of(handle);
    if (!db) {
        throw_runtime(env, "database not open");
        return -1;
    }

    char *s = dup_jstring(env, sql);
    if (!s) {
        throw_runtime(env, "sql is null");
        return -1;
    }

    char *zErr = NULL;
    int rc = sqlite3_exec(db, s, NULL, NULL, &zErr);
    free(s);
    if (rc != SQLITE_OK) {
        throw_sqlite(env, db, NULL, zErr);
        sqlite3_free(zErr);
        return -1;
    }
    return 0;
}

/* ------------------------------------------------------------------ */
/* query                                                               */
/* ------------------------------------------------------------------ */

static int bind_arg(JNIEnv *env, sqlite3_stmt *st, int idx, jobject o,
                    jclass cString, jclass cLong, jclass cInteger,
                    jclass cDouble, jclass cFloat, jclass cBytes) {
    if (o == NULL) return sqlite3_bind_null(st, idx);

    if (cString && (*env)->IsInstanceOf(env, o, cString)) {
        const char *s = (*env)->GetStringUTFChars(env, (jstring)o, NULL);
        int rc = sqlite3_bind_text(st, idx, s, -1, SQLITE_TRANSIENT);
        (*env)->ReleaseStringUTFChars(env, (jstring)o, s);
        return rc;
    }
    if (cLong && (*env)->IsInstanceOf(env, o, cLong)) {
        jclass cls = (*env)->GetObjectClass(env, o);
        jmethodID mid = (*env)->GetMethodID(env, cls, "longValue", "()J");
        jlong v = mid ? (*env)->CallLongMethod(env, o, mid) : 0;
        return sqlite3_bind_int64(st, idx, (sqlite3_int64)v);
    }
    if (cInteger && (*env)->IsInstanceOf(env, o, cInteger)) {
        jclass cls = (*env)->GetObjectClass(env, o);
        jmethodID mid = (*env)->GetMethodID(env, cls, "intValue", "()I");
        jint v = mid ? (*env)->CallIntMethod(env, o, mid) : 0;
        return sqlite3_bind_int64(st, idx, (sqlite3_int64)v);
    }
    if (cDouble && (*env)->IsInstanceOf(env, o, cDouble)) {
        jclass cls = (*env)->GetObjectClass(env, o);
        jmethodID mid = (*env)->GetMethodID(env, cls, "doubleValue", "()D");
        jdouble v = mid ? (*env)->CallDoubleMethod(env, o, mid) : 0;
        return sqlite3_bind_double(st, idx, (double)v);
    }
    if (cFloat && (*env)->IsInstanceOf(env, o, cFloat)) {
        jclass cls = (*env)->GetObjectClass(env, o);
        jmethodID mid = (*env)->GetMethodID(env, cls, "floatValue", "()F");
        jfloat v = mid ? (*env)->CallFloatMethod(env, o, mid) : 0;
        return sqlite3_bind_double(st, idx, (double)v);
    }
    if (cBytes && (*env)->IsInstanceOf(env, o, cBytes)) {
        jbyteArray ba = (jbyteArray)o;
        jsize n = (*env)->GetArrayLength(env, ba);
        void *buf = n ? malloc((size_t)n) : NULL;
        if (n && !buf) return SQLITE_NOMEM;
        if (n) (*env)->GetByteArrayRegion(env, ba, 0, n, (jbyte *)buf);
        int rc = sqlite3_bind_blob(st, idx, buf, n, SQLITE_TRANSIENT);
        free(buf);
        return rc;
    }
    return SQLITE_MISUSE;
}

/* 把一列值转成 Java 对象：NULL / Long / Double / String / byte[] */
static jobject column_to_object(JNIEnv *env, sqlite3_stmt *st, int col,
                                jclass cLong, jclass cDouble) {
    switch (sqlite3_column_type(st, col)) {
        case SQLITE_NULL:
            return NULL;
        case SQLITE_INTEGER: {
            jmethodID mid = (*env)->GetStaticMethodID(env, cLong, "valueOf",
                                                      "(J)Ljava/lang/Long;");
            return mid ? (*env)->CallStaticObjectMethod(
                             env, cLong, mid,
                             (jlong)sqlite3_column_int64(st, col))
                       : NULL;
        }
        case SQLITE_FLOAT: {
            jmethodID mid = (*env)->GetStaticMethodID(env, cDouble, "valueOf",
                                                      "(D)Ljava/lang/Double;");
            return mid ? (*env)->CallStaticObjectMethod(
                             env, cDouble, mid,
                             (jdouble)sqlite3_column_double(st, col))
                       : NULL;
        }
        case SQLITE_BLOB: {
            int n = sqlite3_column_bytes(st, col);
            const void *p = sqlite3_column_blob(st, col);
            jbyteArray ba = (*env)->NewByteArray(env, n);
            if (ba && n) {
                (*env)->SetByteArrayRegion(env, ba, 0, n, (const jbyte *)p);
            }
            return ba;
        }
        default: {
            const unsigned char *t = sqlite3_column_text(st, col);
            return (*env)->NewStringUTF(env, t ? (const char *)t : "");
        }
    }
}

/*
 * 返回 Object[2]：
 *   [0] = String[]  列名
 *   [1] = Object[][] 行（每行是 Object[]，值见 column_to_object）
 */
JNIEXPORT jobjectArray JNICALL
JNI_FN(nativeQuery)(JNIEnv *env, jclass clazz, jlong handle, jstring sql,
                    jobjectArray args, jint maxRows) {
    sqlite3 *db = db_of(handle);
    if (!db) {
        throw_runtime(env, "database not open");
        return NULL;
    }

    char *s = dup_jstring(env, sql);
    if (!s) {
        throw_runtime(env, "sql is null");
        return NULL;
    }

    sqlite3_stmt *st = NULL;
    int rc = sqlite3_prepare_v2(db, s, -1, &st, NULL);
    free(s);
    if (rc != SQLITE_OK) {
        throw_sqlite(env, db, "prepare: ", NULL);
        return NULL;
    }

    jclass cString = (*env)->FindClass(env, "java/lang/String");
    jclass cLong = (*env)->FindClass(env, "java/lang/Long");
    jclass cInteger = (*env)->FindClass(env, "java/lang/Integer");
    jclass cDouble = (*env)->FindClass(env, "java/lang/Double");
    jclass cFloat = (*env)->FindClass(env, "java/lang/Float");
    jclass cBytes = (*env)->FindClass(env, "[B");
    jclass cObject = (*env)->FindClass(env, "java/lang/Object");
    jclass cRowArray = (*env)->FindClass(env, "[Ljava/lang/Object;");

    /* bind */
    int nargs = args ? (*env)->GetArrayLength(env, args) : 0;
    for (int i = 0; i < nargs; i++) {
        jobject a = (*env)->GetObjectArrayElement(env, args, i);
        int brc = bind_arg(env, st, i + 1, a, cString, cLong, cInteger,
                           cDouble, cFloat, cBytes);
        (*env)->DeleteLocalRef(env, a);
        if (brc != SQLITE_OK) {
            sqlite3_finalize(st);
            throw_runtime(env, "unsupported bind argument type "
                               "(String/Long/Integer/Double/Float/byte[]/null only)");
            return NULL;
        }
    }

    int ncols = sqlite3_column_count(st);
    jobjectArray colNames = (*env)->NewObjectArray(env, ncols, cString, NULL);
    for (int c = 0; c < ncols; c++) {
        const char *name = sqlite3_column_name(st, c);
        jstring js = (*env)->NewStringUTF(env, name ? name : "");
        (*env)->SetObjectArrayElement(env, colNames, c, js);
        (*env)->DeleteLocalRef(env, js);
    }

    /* 收集行 */
    int cap = 16, count = 0;
    jobjectArray rows = (*env)->NewObjectArray(env, cap, cRowArray, NULL);

    while ((rc = sqlite3_step(st)) == SQLITE_ROW) {
        if (maxRows > 0 && count >= maxRows) break;
        if (count >= cap) {
            int ncap = cap * 2;
            jobjectArray nrows = (*env)->NewObjectArray(env, ncap, cRowArray, NULL);
            for (int i = 0; i < count; i++) {
                jobject row = (*env)->GetObjectArrayElement(env, rows, i);
                (*env)->SetObjectArrayElement(env, nrows, i, row);
                (*env)->DeleteLocalRef(env, row);
            }
            (*env)->DeleteLocalRef(env, rows);
            rows = nrows;
            cap = ncap;
        }
        jobjectArray row = (*env)->NewObjectArray(env, ncols, cObject, NULL);
        for (int c = 0; c < ncols; c++) {
            jobject v = column_to_object(env, st, c, cLong, cDouble);
            if (v) {
                (*env)->SetObjectArrayElement(env, row, c, v);
                (*env)->DeleteLocalRef(env, v);
            }
        }
        (*env)->SetObjectArrayElement(env, rows, count, row);
        (*env)->DeleteLocalRef(env, row);
        count++;
    }

    int final_rc = rc;
    sqlite3_finalize(st);

    if (final_rc != SQLITE_DONE && final_rc != SQLITE_ROW) {
        throw_sqlite(env, db, "step: ", NULL);
        return NULL;
    }

    /* 行数裁到实际数量 */
    jobjectArray fit = (*env)->NewObjectArray(env, count, cRowArray, NULL);
    for (int i = 0; i < count; i++) {
        jobject row = (*env)->GetObjectArrayElement(env, rows, i);
        (*env)->SetObjectArrayElement(env, fit, i, row);
        (*env)->DeleteLocalRef(env, row);
    }
    (*env)->DeleteLocalRef(env, rows);

    jobjectArray out = (*env)->NewObjectArray(env, 2, cObject, NULL);
    (*env)->SetObjectArrayElement(env, out, 0, colNames);
    (*env)->SetObjectArrayElement(env, out, 1, fit);
    (*env)->DeleteLocalRef(env, colNames);
    (*env)->DeleteLocalRef(env, fit);
    return out;
}
