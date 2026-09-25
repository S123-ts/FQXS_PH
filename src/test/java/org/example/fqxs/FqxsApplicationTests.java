package org.example.fqxs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fqxs-test;DB_CLOSE_DELAY=-1")
class FqxsApplicationTests {

    @Test
    void contextLoads() {
    }

}
