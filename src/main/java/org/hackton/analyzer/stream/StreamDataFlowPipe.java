package org.hackton.analyzer.stream;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.hackton.analyzer.config.ZoneCapacity;
import org.hackton.analyzer.domain.BarrierEvent;
import org.hackton.analyzer.factory.StreamFactory;
import org.hackton.analyzer.transformer.BarrierEventTransformer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.Properties;

import static org.hackton.analyzer.serde.StreamSerdes.barrierEventSerde;
import static org.hackton.analyzer.serde.StreamSerdes.carParkStatusSerde;
import static org.hackton.analyzer.serde.StreamSerdes.stringSerde;


@Slf4j
@Component
public class StreamDataFlowPipe {

    private KafkaStreams streams;

    @Value("${car.park.barrier.event.topic}")
    private String rawSourceTopic;

    @Value("${car.park.store}")
    private String carkParkZoneStoreName;

    @Value("${car.park.availability.output.topic}")
    private String outputTopic;

    final Map<String, String> capacity;

    private final Properties inputStreamProperties;

    public StreamDataFlowPipe(Properties inputStreamProperties, Map<String, String> capacity){
        this.inputStreamProperties = inputStreamProperties;
        this.capacity = capacity;
    }

    Topology topology() {

        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, BarrierEvent> barrierEventKStream = builder.stream(rawSourceTopic, Consumed.with(Serdes.String(), barrierEventSerde));

        // Create a window state store
        builder.addStateStore(StreamFactory.createInMemoryStore(carkParkZoneStoreName));

        KStream<String, Map> carkParkStatus = barrierEventKStream.transformValues(() -> new BarrierEventTransformer(carkParkZoneStoreName, capacity), carkParkZoneStoreName)
                .map((key, CarParkStatus) -> CarParkStatus)
                .peek((k, v)-> log.info("key: {} value: {}" , k, v));

        carkParkStatus.to(outputTopic, Produced.with(stringSerde, carParkStatusSerde));

        return builder.build();
    }

    @PostConstruct
    public void runStream() {

        Topology topology = topology();
        log.info("topology description {}", topology.describe());
        streams = new KafkaStreams(topology, inputStreamProperties);
        streams.setUncaughtExceptionHandler((t, e) -> {
            log.error("Thread {} had a fatal error {}", t, e, e);
            closeStream();
        });

        KafkaStreams.StateListener stateListener = (newState, oldState) -> {
            if (newState == KafkaStreams.State.RUNNING && oldState == KafkaStreams.State.REBALANCING) {
                log.info("Application has gone from REBALANCING to RUNNING ");
                log.info("Topology Layout {}", topology.describe());
            }

            if (newState == KafkaStreams.State.REBALANCING) {
                log.info("Application is entering REBALANCING phase");
            }

            if (!newState.isRunning()) {
                log.info("Application is not in a RUNNING phase");
            }
        };

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            closeStream();
        }));
        streams.start();
    }

    @PreDestroy
    public void closeStream() {
        log.info("Closing Car Park Analyzer..");
        streams.close();
        streams.cleanUp();
    }
}
