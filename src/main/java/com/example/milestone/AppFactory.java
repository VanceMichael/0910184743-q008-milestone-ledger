package com.example.milestone;

import com.example.milestone.fault.FaultInjector;
import com.example.milestone.http.ApiHandlers;
import com.example.milestone.http.HttpApp;
import com.example.milestone.service.ApprovalService;
import com.example.milestone.service.EvidenceService;
import com.example.milestone.service.ProjectService;
import com.example.milestone.service.QueryService;
import com.example.milestone.service.ViewSanitizer;
import com.example.milestone.store.LedgerStore;

import javax.sql.DataSource;
import java.io.IOException;

/** 应用装配：Main 与集成测试共用。 */
public final class AppFactory {

    private AppFactory() {}

    public static HttpApp create(DataSource ds, FaultInjector faults, int port) throws IOException {
        var store = new LedgerStore();
        var sanitizer = new ViewSanitizer(store);
        var projects = new ProjectService(ds, store);
        var evidence = new EvidenceService(ds, store);
        var approvals = new ApprovalService(ds, store, faults);
        var queries = new QueryService(ds, store, sanitizer);
        return new HttpApp(port, ApiHandlers.build(ds, projects, evidence, approvals, queries));
    }
}
