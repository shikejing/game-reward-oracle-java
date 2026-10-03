package com.github.shikejing.gamerewardoraclejava.web;

import com.github.shikejing.gamerewardoraclejava.service.OracleService;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportRequest;
import com.github.shikejing.gamerewardoraclejava.web.dto.ReportResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地模拟预言机上报入口。
 */
@RestController
@RequestMapping("/api/oracle")
public class OracleController {

    private final OracleService oracleService;

    public OracleController(OracleService oracleService) {
        this.oracleService = oracleService;
    }

    @PostMapping("/reports")
    public ReportResponse report(@RequestBody ReportRequest request) {
        return oracleService.report(request);
    }
}
