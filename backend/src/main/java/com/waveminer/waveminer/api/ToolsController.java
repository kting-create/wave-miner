package com.waveminer.waveminer.api;

import com.waveminer.waveminer.media.ToolHealthChecker;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
public class ToolsController {

    private final ToolHealthChecker checker;

    public ToolsController(ToolHealthChecker checker) { this.checker = checker; }

    @GetMapping("/health")
    public List<ToolHealthChecker.ToolHealth> health() {
        return checker.checkAll();
    }
}
