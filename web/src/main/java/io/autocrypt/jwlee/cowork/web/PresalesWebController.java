package io.autocrypt.jwlee.cowork.web;

import io.autocrypt.jwlee.cowork.agents.presales.PresalesAgent;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/presales")
public class PresalesWebController {

    private final PresalesWebService presalesWebService;

    public PresalesWebController(PresalesWebService presalesWebService) {
        this.presalesWebService = presalesWebService;
    }

    @GetMapping("/setup")
    public String setup(Model model) {
        model.addAttribute("techSpecs", presalesWebService.getTechSpecs());
        model.addAttribute("productSpecs", presalesWebService.getProductSpecs());
        model.addAttribute("inquiryFileName", presalesWebService.getInquiryFileName());
        return "analysis_setup";
    }

    @PostMapping("/upload-tech")
    public String uploadTech(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {
        try {
            presalesWebService.addTechSpec(file);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to upload tech spec: " + e.getMessage());
        }
        return "redirect:/presales/setup";
    }

    @PostMapping("/upload-product")
    public String uploadProduct(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {
        try {
            presalesWebService.addProductSpec(file);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to upload product spec: " + e.getMessage());
        }
        return "redirect:/presales/setup";
    }

    @PostMapping("/upload-inquiry")
    public String uploadInquiry(@RequestParam("file") MultipartFile file, RedirectAttributes redirectAttributes) {
        try {
            presalesWebService.setInquiry(file);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Failed to upload inquiry: " + e.getMessage());
        }
        return "redirect:/presales/setup";
    }

    @PostMapping("/start")
    public String startAnalysis(Model model) {
        try {
            PresalesAgent.AnalysisResult result = presalesWebService.runAnalysis();
            model.addAttribute("result", result);
            return "analysis_results";
        } catch (Exception e) {
            model.addAttribute("error", "Analysis failed: " + e.getMessage());
            return "analysis_setup";
        }
    }

    @GetMapping("/report")
    public String report() {
        return "eval_report";
    }
}
