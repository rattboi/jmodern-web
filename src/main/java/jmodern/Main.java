package jmodern;

import com.codahale.metrics.*;
import com.codahale.metrics.annotation.*;
import com.fasterxml.jackson.annotation.*;
import com.google.common.base.Optional;
import io.dropwizard.Application;
import io.dropwizard.*;
import io.dropwizard.setup.*;
import io.dropwizard.client.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import org.hibernate.validator.constraints.*;
import com.sun.jersey.api.client.Client;
import feign.Feign;
import feign.jackson.*;
import feign.jaxrs.*;

public class Main extends Application<Main.JModernConfiguration> {
    public static void main(String[] args) throws Exception {
        new Main().run(new String[]{"server", System.getProperty("dropwizard.config")});
    }

    @Override
    public void initialize(Bootstrap<JModernConfiguration> bootstrap) {
    }

    @Override
    public void run(JModernConfiguration cfg, Environment env) {
        JmxReporter.forRegistry(env.metrics()).build().start(); //Manually add JMX reporting
        Feign.Builder feignBuilder = Feign.builder()
            .contract(new JAXRSModule.JAXRSContract()) // we want JAX-RS annotations
            .encoder(new JacksonEncoder()) // we want Jackson because that's what Dropwizard uses already
            .decoder(new JacksonDecoder());

        env.jersey().register(new HelloWorldResource(cfg));
        env.jersey().register(new ConsumerResource(feignBuilder));
    }

    // YAML Configuration
    public static class JModernConfiguration extends Configuration {
        @JsonProperty private @NotEmpty String template;
        @JsonProperty private @NotEmpty String defaultName;

        @Valid
        @NotNull
        @JsonProperty JerseyClientConfiguration httpClient = new JerseyClientConfiguration();
        public JerseyClientConfiguration getJerseyClientConfiguration() { return httpClient; }

        public String getTemplate()     { return template; };
        public String getDefaultName()  { return defaultName; };
    }

    @Path("/hello-world")
    @Produces(MediaType.APPLICATION_JSON)
    public static class HelloWorldResource {
        private final AtomicLong counter = new AtomicLong();
        private final String template;
        private final String defaultName;

        public HelloWorldResource(JModernConfiguration cfg) {
            this.template = cfg.getTemplate();
            this.defaultName = cfg.getDefaultName();
        }

        @Timed // monitory timing of this service with Metrics
        @GET
        public Saying sayHello(@QueryParam("name") Optional<String> name) throws InterruptedException {
            final String value = String.format(template, name.or(defaultName));
            Thread.sleep(ThreadLocalRandom.current().nextInt(10, 500));
            return new Saying(counter.incrementAndGet(), value);
        }
    }

    @Path("/consumer")
    @Produces(MediaType.TEXT_PLAIN)
    public static class ConsumerResource {
        private final HelloWorldAPI helloWorld;

        public ConsumerResource(Feign.Builder feignBuilder) {
            this.helloWorld = feignBuilder.target(HelloWorldAPI.class, "http://localhost:8080");
        }

        @Timed
        @GET
        public String consume() {
            Saying saying = helloWorld.hi("consumer");
            return String.format("The service is saying: %s (id: %d)", saying.getContent(), saying.getId());
        }
    }

    // JSON (immutable!) payload
    public static class Saying {
        private long id;
        private @Length(max = 10) String content;

        public Saying(long id, String content) {
            this.id = id;
            this.content = content;
        }

        public Saying() {} //required for deserialization

        @JsonProperty public long getId()        { return id; }
        @JsonProperty public String getContent() { return content; }
    }

    interface HelloWorldAPI {
        @GET @Path("/hello-world")
            Saying hi(@QueryParam("name") String name);

        @GET @Path("/hello-world")
            Saying hi();
    }
}
