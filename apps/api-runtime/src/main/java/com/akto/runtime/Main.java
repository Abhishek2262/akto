package com.akto.runtime;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.akto.DaoInit;
import com.akto.dao.*;
import com.akto.dao.context.Context;
import com.akto.dto.APIConfig;
import com.akto.dto.AccountSettings;
import com.akto.dto.ApiCollection;
import com.akto.dto.type.SingleTypeInfo;
import com.akto.kafka.Kafka;
import com.akto.parsers.HttpCallParser;
import com.akto.dto.HttpResponseParams;
import com.akto.runtime.policies.AktoPolicies;
import com.google.gson.Gson;
import com.mongodb.ConnectionString;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private Consumer<String, String> consumer;
    public static final String GROUP_NAME = "group_name";
    public static final String VXLAN_ID = "vxlanId";
    public static final String VPC_CIDR = "vpc_cidr";
    private static final Logger logger = LoggerFactory.getLogger(HttpCallParser.class);

    // this sync threshold time is used for deleting sample data
    public static final int sync_threshold_time = 120;

    private static int debugPrintCounter = 500;
    private static void printL(Object o) {
        if (debugPrintCounter > 0) {
            debugPrintCounter--;
            logger.info(o.toString());
        }
    }   

    public static boolean tryForCollectionName(String message) {
        boolean ret = false;
        try {
            Gson gson = new Gson();

            Map<String, Object> json = gson.fromJson(message, Map.class);

            // logger.info("Json size: " + json.size());
            boolean withoutCidrCond = json.size() == 2 && json.containsKey(GROUP_NAME) && json.containsKey(VXLAN_ID);
            boolean withCidrCond = json.size() == 3 && json.containsKey(GROUP_NAME) && json.containsKey(VXLAN_ID) && json.containsKey(VPC_CIDR);
            if (withCidrCond || withoutCidrCond) {
                ret = true;
                String groupName = (String) (json.get(GROUP_NAME));
                String vxlanIdStr = ((Double) json.get(VXLAN_ID)).intValue() + "";
                int vxlanId = Integer.parseInt(vxlanIdStr);
                ApiCollectionsDao.instance.getMCollection().updateMany(
                        Filters.eq(ApiCollection.VXLAN_ID, vxlanId),
                        Updates.set(ApiCollection.NAME, groupName)
                );

                if (json.size() == 3) {
                    List<String> cidrList = (List<String>) json.get(VPC_CIDR);
                    logger.info("cidrList: " + cidrList);
                    AccountSettingsDao.instance.getMCollection().updateOne(
                        AccountSettingsDao.generateFilter(), Updates.addEachToSet("privateCidrList", cidrList), new UpdateOptions().upsert(true)
                    );
                }
            }
        } catch (Exception e) {
            logger.error("error in try collection", e);
        }

        return ret;
    }


    public static void createIndices() {
        SingleTypeInfoDao.instance.createIndicesIfAbsent();
        SensitiveSampleDataDao.instance.createIndicesIfAbsent();
        SampleDataDao.instance.createIndicesIfAbsent();
    }

    public static void insertRuntimeFilters() {
        RuntimeFilterDao.instance.initialiseFilters();
    }

    public static Kafka kafkaProducer = null;
    private static void buildKafka(int accountId) {
        Context.accountId.set(accountId);
        AccountSettings accountSettings = AccountSettingsDao.instance.findOne(AccountSettingsDao.generateFilter(accountId));
        if (accountSettings != null && accountSettings.getCentralKafkaIp()!= null) {
            String centralKafkaBrokerUrl = accountSettings.getCentralKafkaIp();
            int centralKafkaBatchSize = AccountSettings.DEFAULT_CENTRAL_KAFKA_BATCH_SIZE;
            int centralKafkaLingerMS = AccountSettings.DEFAULT_CENTRAL_KAFKA_LINGER_MS;
            if (centralKafkaBrokerUrl != null) {
                kafkaProducer = new Kafka(centralKafkaBrokerUrl, centralKafkaLingerMS, centralKafkaBatchSize);
                logger.info("Connected to central kafka @ " + Context.now());
            }
        }
    }

    public static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    // REFERENCE: https://www.oreilly.com/library/view/kafka-the-definitive/9781491936153/ch04.html (But how do we Exit?)
    public static void main(String[] args) {
        String mongoURI = System.getenv("AKTO_MONGO_CONN");;
        String configName = System.getenv("AKTO_CONFIG_NAME");
        String topicName = System.getenv("AKTO_KAFKA_TOPIC_NAME");
        String kafkaBrokerUrl = "kafka1:19092"; //System.getenv("AKTO_KAFKA_BROKER_URL");
        String groupIdConfig =  System.getenv("AKTO_KAFKA_GROUP_ID_CONFIG");
        String instanceType =  System.getenv("AKTO_INSTANCE_TYPE");
        boolean syncImmediately = false;
        boolean fetchAllSTI = true;
        if (instanceType != null && instanceType.equals("DASHBOARD")) {
            syncImmediately = true;
            fetchAllSTI = false;
        }
        int maxPollRecordsConfig = Integer.parseInt(System.getenv("AKTO_KAFKA_MAX_POLL_RECORDS_CONFIG"));

        if (topicName == null) topicName = "akto.api.logs";

        // mongoURI = "mongodb://write_ops:write_ops@cluster0-shard-00-00.yg43a.mongodb.net:27017,cluster0-shard-00-01.yg43a.mongodb.net:27017,cluster0-shard-00-02.yg43a.mongodb.net:27017/myFirstDatabase?ssl=true&replicaSet=atlas-qd3mle-shard-0&authSource=admin&retryWrites=true&w=majority";
        DaoInit.init(new ConnectionString(mongoURI));
        Context.accountId.set(1_000_000);
        initializeRuntime();

        String centralKafkaTopicName = AccountSettings.DEFAULT_CENTRAL_KAFKA_TOPIC_NAME;

        int accountIdHardcoded = Context.accountId.get();
        buildKafka(accountIdHardcoded);
        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                if (kafkaProducer == null || !kafkaProducer.producerReady) {
                    buildKafka(accountIdHardcoded);
                }
            }
        }, 5, 5, TimeUnit.MINUTES);


        APIConfig apiConfig;
        apiConfig = APIConfigsDao.instance.findOne(Filters.eq("name", configName));
        if (apiConfig == null) {
            apiConfig = new APIConfig(configName,"access-token", 1, 10_000_000, sync_threshold_time); // this sync threshold time is used for deleting sample data
        }

        final Main main = new Main();
        Properties properties = main.configProperties(kafkaBrokerUrl, groupIdConfig, maxPollRecordsConfig);
        main.consumer = new KafkaConsumer<>(properties);

        final Thread mainThread = Thread.currentThread();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                main.consumer.wakeup();
                try {
                    mainThread.join();
                } catch (InterruptedException e) {
                    ;
                }
            }
        });

        Map<String, HttpCallParser> httpCallParserMap = new HashMap<>();
        Map<String, Flow> flowMap = new HashMap<>();
        Map<String, AktoPolicies> aktoPolicyMap = new HashMap<>();

        // sync infra metrics thread
        // ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
        // KafkaHealthMetricSyncTask task = new KafkaHealthMetricSyncTask(main.consumer);
        // executor.scheduleAtFixedRate(task, 2, 60, TimeUnit.SECONDS);

        long lastSyncOffset = 0;

        try {
            main.consumer.subscribe(Arrays.asList(topicName, "har_"+topicName));
            while (true) {
                ConsumerRecords<String, String> records = main.consumer.poll(Duration.ofMillis(10000));
                main.consumer.commitSync();

                Map<String, List<HttpResponseParams>> responseParamsToAccountMap = new HashMap<>();
                for (ConsumerRecord<String,String> r: records) {
                    HttpResponseParams httpResponseParams;
                    try {
                         
                        printL(r.value());
                        lastSyncOffset++;

                        if (lastSyncOffset % 100 == 0) {
                            logger.info("Committing offset at position: " + lastSyncOffset);
                        }

                        if (tryForCollectionName(r.value())) {
                            continue;
                        }

                        httpResponseParams = HttpCallParser.parseKafkaMessage(r.value());
                         
                    } catch (Exception e) {
                        logger.error("Error while parsing kafka message " + e);
                        continue;
                    }
                    String accountId = httpResponseParams.getAccountId();
                    if (!responseParamsToAccountMap.containsKey(accountId)) {
                        responseParamsToAccountMap.put(accountId, new ArrayList<>());
                    }
                    responseParamsToAccountMap.get(accountId).add(httpResponseParams);
                }

                for (String accountId: responseParamsToAccountMap.keySet()) {
                    int accountIdInt;
                    try {
                        accountIdInt = Integer.parseInt(accountId);
                    } catch (Exception ignored) {
                        logger.info("Account id not string");
                        continue;
                    }

                    Context.accountId.set(accountIdInt);

                    if (!httpCallParserMap.containsKey(accountId)) {
                        HttpCallParser parser = new HttpCallParser(
                                apiConfig.getUserIdentifier(), apiConfig.getThreshold(), apiConfig.getSync_threshold_count(),
                                apiConfig.getSync_threshold_time(), fetchAllSTI
                        );

                        httpCallParserMap.put(accountId, parser);
                    }

                    if (!flowMap.containsKey(accountId)) {
                        Flow flow= new Flow(
                                apiConfig.getThreshold(), apiConfig.getSync_threshold_count(), apiConfig.getSync_threshold_time(),
                                apiConfig.getThreshold(), apiConfig.getSync_threshold_count(), apiConfig.getSync_threshold_time(),
                                apiConfig.getUserIdentifier()
                        );

                        flowMap.put(accountId, flow);
                    }

                    if (!aktoPolicyMap.containsKey(accountId)) {
                        APICatalogSync apiCatalogSync = httpCallParserMap.get(accountId).apiCatalogSync;
                        AktoPolicies aktoPolicy = new AktoPolicies(apiCatalogSync, fetchAllSTI);
                        aktoPolicyMap.put(accountId, aktoPolicy);
                    }

                    HttpCallParser parser = httpCallParserMap.get(accountId);
                    // Flow flow = flowMap.get(accountId);
                    AktoPolicies aktoPolicy = aktoPolicyMap.get(accountId);

                    try {
                        List<HttpResponseParams> accWiseResponse = responseParamsToAccountMap.get(accountId);
                        APICatalogSync apiCatalogSync = parser.syncFunction(accWiseResponse, syncImmediately, fetchAllSTI);

                        // send to central kafka
                        if (kafkaProducer != null) {
                            for (HttpResponseParams httpResponseParams: accWiseResponse) {
                                try {
                                    kafkaProducer.send(httpResponseParams.getOrig(), centralKafkaTopicName);
                                } catch (Exception e) {
                                    // force close it
                                    kafkaProducer.close();
                                    logger.error(e.getMessage());
                                }
                            }
                        }

                        // flow.init(accWiseResponse);
                        aktoPolicy.main(accWiseResponse, apiCatalogSync, fetchAllSTI);
                    } catch (Exception e) {
                        logger.error(e.toString());
                    }
                }
            }

        } catch (WakeupException ignored) {
          // nothing to catch. This exception is called from the shutdown hook.
        } catch (Exception e) {
            printL(e);
            ;
        } finally {
            main.consumer.close();
        }
    }

    public static void initializeRuntime(){
        SingleTypeInfoDao.instance.getMCollection().updateMany(Filters.exists("apiCollectionId", false), Updates.set("apiCollectionId", 0));
        SingleTypeInfo.init();

        createIndices();
        insertRuntimeFilters();
        try {
            AccountSettingsDao.instance.updateVersion(AccountSettings.API_RUNTIME_VERSION);
        } catch (Exception e) {
            logger.error("error while updating dashboard version: " + e.getMessage());
        }

        ApiCollection apiCollection = ApiCollectionsDao.instance.findOne("_id", 0);
        if (apiCollection == null) {
            Set<String> urls = new HashSet<>();
            for(SingleTypeInfo singleTypeInfo: SingleTypeInfoDao.instance.fetchAll()) {
                urls.add(singleTypeInfo.getUrl());
            }
            ApiCollectionsDao.instance.insertOne(new ApiCollection(0, "Default", Context.now(), urls, null, 0));
        }
    }


    public static Properties configProperties(String kafkaBrokerUrl, String groupIdConfig, int maxPollRecordsConfig) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokerUrl);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecordsConfig);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupIdConfig);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return properties;
    }
}