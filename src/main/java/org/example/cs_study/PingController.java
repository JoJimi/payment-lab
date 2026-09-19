package org.example.cs_study;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
class PingController {
    @GetMapping("/ping")
    String ping() {
        log.info("ping 요청 수신");
        return "pong";
    }
}
