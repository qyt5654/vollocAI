package com.vollocAI.ai.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);
    private static final String COL = "knowledge_base";

    private MilvusServiceClient client;

    @Bean
    public MilvusServiceClient milvusClient() {
        client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost("localhost").withPort(19530).build());
        ensureCollection();
        log.info("Milvus 就绪 collection={}", COL);
        return client;
    }

    private void ensureCollection() {
        if (client.hasCollection(HasCollectionParam.newBuilder().withCollectionName(COL).build()).getData())
            return;
        client.createCollection(CreateCollectionParam.newBuilder().withCollectionName(COL)
                .withFieldTypes(Arrays.asList(
                        FieldType.newBuilder().withName("id").withDataType(io.milvus.grpc.DataType.Int64)
                                .withPrimaryKey(true).withAutoID(true).build(),
                        FieldType.newBuilder().withName("doc_id").withDataType(io.milvus.grpc.DataType.VarChar)
                                .withMaxLength(64).build(),
                        FieldType.newBuilder().withName("content").withDataType(io.milvus.grpc.DataType.VarChar)
                                .withMaxLength(4096).build(),
                        FieldType.newBuilder().withName("embedding").withDataType(io.milvus.grpc.DataType.FloatVector)
                                .withDimension(1536).build()))
                .build());
        client.createIndex(CreateIndexParam.newBuilder().withCollectionName(COL)
                .withFieldName("embedding").withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.IP).withExtraParam("{\"nlist\":128}").build());
        client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(COL).build());
        log.info("Milvus collection [{}] 已创建", COL);
    }

    @PreDestroy public void close() { if (client != null) client.close(); }
}
