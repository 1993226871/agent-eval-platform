package io.agenteval.worker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.time.Clock;
@EnableScheduling
@SpringBootApplication(scanBasePackages="io.agenteval")
public class PlatformWorkerApplication {
    public static void main(String[] args) { var application=new SpringApplication(PlatformWorkerApplication.class); application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE); application.run(args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
