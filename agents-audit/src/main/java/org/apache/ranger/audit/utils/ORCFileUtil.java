package org.apache.ranger.audit.utils;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.ColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DecimalColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.DoubleColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.orc.CompressionKind;
import org.apache.orc.OrcFile;
import org.apache.orc.OrcFile.WriterOptions;
import org.apache.orc.TypeDescription;
import org.apache.orc.Writer;
import org.apache.ranger.audit.model.AuthzAuditEvent;
import org.apache.ranger.audit.model.EnumRepositoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class ORCFileUtil {
    private static final Logger logger = LoggerFactory.getLogger(ORCFileUtil.class);

    private static volatile ORCFileUtil me;

    protected CompressionKind    defaultCompression = CompressionKind.SNAPPY;
    protected CompressionKind    compressionKind    = CompressionKind.NONE;
    protected TypeDescription    schema;
    protected VectorizedRowBatch batch;
    protected String             auditSchema;
    protected String             dateFormat         = "yyyy-MM-dd HH:mm:ss";

    protected ArrayList<String>         schemaFields          = new ArrayList<>();
    protected Map<String, ColumnVector> vectorizedRowBatchMap = new HashMap<>();
    protected int                       orcBufferSize;
    protected long                      orcStripeSize;

    public static ORCFileUtil getInstance() {
        ORCFileUtil orcFileUtil = me;

        if (orcFileUtil == null) {
            synchronized (ORCFileUtil.class) {
                orcFileUtil = me;

                if (orcFileUtil == null) {
                    orcFileUtil = new ORCFileUtil();
                    me          = orcFileUtil;
                }
            }
        }

        return orcFileUtil;
    }

    public static void main(String[] args) throws Exception {
        ORCFileUtil auditOrcFileUtil = new ORCFileUtil();

        auditOrcFileUtil.init(10000, 100000L, "snappy");

        try {
            Configuration               conf   = new Configuration();
            FileSystem                  fs     = FileSystem.get(conf);
            Writer                      write  = auditOrcFileUtil.createWriter(conf, fs, "/tmp/test.orc");
            Collection<AuthzAuditEvent> events = getTestEvent();

            auditOrcFileUtil.log(write, events);

            write.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected static Collection<AuthzAuditEvent> getTestEvent() {
        Collection<AuthzAuditEvent> events = new ArrayList<>();

        for (int idx = 0; idx < 20; idx++) {
            AuthzAuditEvent event = new AuthzAuditEvent();

            event.setEventId(Integer.toString(idx));
            event.setClientIP("127.0.0.1");
            event.setAccessResult((short) 1);
            event.setAclEnforcer("ranger-acl");
            event.setRepositoryName("hdfsdev");
            event.setRepositoryType(EnumRepositoryType.HDFS);
            event.setResourcePath("/tmp/test-audit.log" + idx + idx + 1);
            event.setResourceType("file");
            event.setAccessType("read");
            event.setEventTime(new Date());
            event.setResultReason(Integer.toString(1));

            events.add(event);
        }

        return events;
    }

    public void init(int orcBufferSize, long orcStripeSize, String compression) throws Exception {
        logger.debug("==> ORCFileUtil.init()");

        this.orcBufferSize   = orcBufferSize;
        this.orcStripeSize   = orcStripeSize;
        this.compressionKind = getORCCompression(compression);

        initORCAuditSchema();

        logger.debug("<== ORCFileUtil.init() : orcBufferSize: {} stripeSize: {} compression: {}", orcBufferSize, orcStripeSize, compression);
    }

    public Writer createWriter(Configuration conf, FileSystem fs, String path) throws Exception {
        logger.debug("==> ORCFileUtil.createWriter()");

        WriterOptions writeOptions = OrcFile.writerOptions(conf)
                .fileSystem(fs)
                .setSchema(schema)
                .bufferSize(orcBufferSize)
                .stripeSize(orcStripeSize)
                .compress(compressionKind);

        Writer ret = OrcFile.createWriter(new Path(path), writeOptions);

        logger.debug("<== ORCFileUtil.createWriter()");

        return ret;
    }

    public void close(Writer writer) throws Exception {
        logger.debug("==> ORCFileUtil.close()");

        writer.close();

        logger.debug("<== ORCFileUtil.close()");
    }

    public void log(Writer writer, Collection<AuthzAuditEvent> events) throws Exception {
        int eventBatchSize = events.size();

        logger.debug("==> ORCFileUtil.log() : EventSize: {} ORC bufferSize:{}", eventBatchSize, orcBufferSize);

        try {
            for (AuthzAuditEvent event : events) {
                int row = batch.size++;

                for (String fieldName : schemaFields) {
                    SchemaInfo   schemaInfo   = getFieldValue(event, fieldName);
                    ColumnVector columnVector = vectorizedRowBatchMap.get(fieldName);

                    if (columnVector instanceof LongColumnVector) {
                        ((LongColumnVector) columnVector).vector[row] = castLongObject(schemaInfo.getValue());
                    } else if (columnVector instanceof BytesColumnVector) {
                        ((BytesColumnVector) columnVector).setVal(row, getBytesValues(castStringObject(schemaInfo.getValue())));
                    }
                }

                if (batch.size == orcBufferSize) {
                    writer.addRowBatch(batch);

                    batch.reset();
                }
            }

            if (batch.size != 0) {
                writer.addRowBatch(batch);

                batch.reset();
            }
        } catch (Exception e) {
            batch.reset();

            logger.error("Error while writing into ORC File:", e);

            throw e;
        }

        logger.debug("<== ORCFileUtil.log(): EventSize = {}", eventBatchSize);
    }

    protected byte[] getBytesValues(String val) {
        byte[] ret = "".getBytes();

        if (val != null) {
            ret = val.getBytes();
        }

        return ret;
    }

    protected String getDateString(Date date) {
        Format formatter = new SimpleDateFormat(dateFormat);

        return formatter.format(date);
    }

    protected void initORCAuditSchema() throws Exception {
        logger.debug("==> ORCWriter.initORCAuditSchema()");

        auditSchema = getAuditSchema();

        Map<String, String> schemaFieldTypeMap = getSchemaFieldTypeMap();

        schema = TypeDescription.fromString(auditSchema);
        batch  = schema.createRowBatch(orcBufferSize);

        buildVectorRowBatch(schemaFieldTypeMap);

        logger.debug("<== ORCWriter.initORCAuditSchema()");
    }

    protected Map<String, String> getSchemaFieldTypeMap() {
        Map<String, String> ret = new HashMap<>();

        int      index1         = auditSchema.indexOf("<");
        int      index2         = auditSchema.indexOf(">");
        String   subAuditSchema = auditSchema.substring(index1 + 1, index2);
        String[] fields         = subAuditSchema.split(",");

        schemaFields = new ArrayList<>();

        for (String field : fields) {
            String[] flds = field.split(":");

            schemaFields.add(flds[0]);

            ret.put(flds[0], flds[1]);
        }

        return ret;
    }

    protected void buildVectorRowBatch(Map<String, String> schemaFieldTypeMap) throws Exception {
        for (int i = 0; i < schemaFields.size(); i++) {
            String       fld          = schemaFields.get(i);
            String       fieldType    = schemaFieldTypeMap.get(fld);
            ColumnVector columnVector = getColumnVectorType(fieldType);

            if (columnVector instanceof LongColumnVector) {
                vectorizedRowBatchMap.put(fld, batch.cols[i]);
            } else if (columnVector instanceof BytesColumnVector) {
                vectorizedRowBatchMap.put(fld, batch.cols[i]);
            } else if (columnVector instanceof DecimalColumnVector) {
                vectorizedRowBatchMap.put(fld, batch.cols[i]);
            }
        }
    }

    protected SchemaInfo getFieldValue(AuthzAuditEvent event, String fieldName) {
        SchemaInfo ret = new SchemaInfo();

        try {
            Class<AuthzAuditEvent> aClass = AuthzAuditEvent.class;
            Field                  fld    = aClass.getDeclaredField(fieldName);

            fld.setAccessible(true);

            Class<?> cls   = fld.getType();
            Object   value = fld.get(event);

            ret.setField(fieldName);
            ret.setType(cls.getName());
            ret.setValue(value);
        } catch (Exception e) {
            logger.error("Error while writing into ORC File:", e);
        }
        return ret;
    }

    protected ColumnVector getColumnVectorType(String fieldType) throws Exception {
        final ColumnVector ret;

        fieldType = fieldType.toLowerCase();

        switch (fieldType) {
            case "int":
            case "bigint":
            case "date":
            case "boolean":
                ret = new LongColumnVector();
                break;
            case "string":
            case "varchar":
            case "char":
            case "binary":
                ret = new BytesColumnVector();
                break;
            case "decimal":
                ret = new DecimalColumnVector(10, 5);
                break;
            case "double":
            case "float":
                ret = new DoubleColumnVector();
                break;
            case "array":
            case "map":
            case "uniontype":
            case "struct":
                throw new Exception("Unsuppoted field Type");
            default:
                ret = null;
                break;
        }

        return ret;
    }

    protected Long castLongObject(Object object) {
        long ret = 0L;

        try {
            if (object instanceof Long) {
                ret = ((Long) object);
            } else if (object instanceof Integer) {
                ret = ((Integer) object).longValue();
            } else if (object instanceof String) {
                ret = Long.parseLong((String) object);
            }
        } catch (Exception e) {
            logger.error("Error while writing into ORC File:", e);
        }

        return ret;
    }

    protected String castStringObject(Object object) {
        String ret = null;

        try {
            if (object instanceof String) {
                ret = (String) object;
            } else if (object instanceof Date) {
                ret = (getDateString((Date) object));
            }
        } catch (Exception e) {
            logger.error("Error while writing into ORC File:", e);
        }

        return ret;
    }

    protected String getAuditSchema() {
        logger.debug("==> ORCWriter.getAuditSchema()");

        String                 fieldStr        = "struct<";
        StringBuilder          sb              = new StringBuilder(fieldStr);
        Class<AuthzAuditEvent> auditEventClass = AuthzAuditEvent.class;

        for (Field fld : auditEventClass.getDeclaredFields()) {
            if (fld.isAnnotationPresent(JsonProperty.class)) {
                String field     = fld.getName();
                String fieldType = getShortFieldType(fld.getType().getName());

                if (fieldType == null) {
                    continue;
                }

                fieldStr = field + ":" + fieldType + ",";

                sb.append(fieldStr);
            }
        }

        fieldStr = sb.toString();

        if (fieldStr.endsWith(",")) {
            fieldStr = fieldStr.substring(0, fieldStr.length() - 1);
        }

        String ret = fieldStr + ">";

        logger.debug("<== ORCWriter.getAuditSchema()  AuditSchema: {}", ret);

        return ret;
    }

    protected String getShortFieldType(String type) {
        final String ret;

        switch (type) {
            case "java.lang.String":
                ret = "string";
                break;
            case "int":
                ret = "int";
                break;
            case "short":
                ret = "string";
                break;
            case "java.util.Date":
                ret = "string";
                break;
            case "long":
                ret = "bigint";
                break;
            default:
                ret = null;
                break;
        }

        return ret;
    }

    protected CompressionKind getORCCompression(String compression) {
        final CompressionKind ret;

        if (compression == null) {
            compression = defaultCompression.name().toLowerCase();
        }

        switch (compression) {
            case "snappy":
                ret = CompressionKind.SNAPPY;
                break;
            case "lzo":
                ret = CompressionKind.LZO;
                break;
            case "zlib":
                ret = CompressionKind.ZLIB;
                break;
            case "none":
                ret = CompressionKind.NONE;
                break;
            default:
                ret = defaultCompression;
                break;
        }

        return ret;
    }

    static class SchemaInfo {
        String field;
        String type;
        Object value;

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }
    }
}
