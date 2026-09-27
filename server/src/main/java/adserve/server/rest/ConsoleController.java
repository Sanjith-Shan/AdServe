package adserve.server.rest;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the delivery console (built from ui/ into static/console). */
@Controller
public class ConsoleController {
    @GetMapping({"/console", "/console/"})
    public String console() {
        return "forward:/console/index.html";
    }
}
