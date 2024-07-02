package com.adt.payroll.service;

import com.adt.payroll.dto.AppraisalDetailsDTO;
import com.adt.payroll.model.AppraisalDetails;
import com.adt.payroll.model.MonthlySalaryDetails;
import com.adt.payroll.model.Reward;
import com.adt.payroll.model.User;
import com.adt.payroll.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class AppraisalDetailsServiceImpl implements AppraisalDetailsService,MonthlySalaryService {
    @Autowired
    private AppraisalDetailsRepository appraisalDetailsRepository;

    //@Autowired
    //  private EmployeeRepo employeeRepo;
    @Autowired
    private UserRepo userRepo;
    @Autowired
    private MonthlySalaryDetailsRepo monthlySalaryDetailsRepo;
    @Autowired
    private RewardDetailsRepository rewardDetailsRepository;

    @Override
    public ResponseEntity<List<AppraisalDetails>> getAppraisalDetails(Integer id) {
        Optional<User> user = userRepo.findByEmployeeId(id);
        if (user.isPresent()) {
            List<AppraisalDetails> appraisalDetailsList = appraisalDetailsRepository.findByEmployee_Id(id);
            appraisalDetailsList.stream().forEach(e -> {
                AppraisalDetailsDTO dto = new AppraisalDetailsDTO();
                dto.setAppraisalDate(e.getAppraisalDate());
                dto.setMonth(e.getMonth());
                dto.setYear(e.getYear());
                dto.setBonus(e.getBonus());
                dto.setAmount(e.getAmount());
                dto.setAppr_hist_id(e.getAppr_hist_id());
                dto.setEmpId(e.getEmpId());
                dto.setVariable(e.getVariable());
                dto.setSalary(e.getSalary());
                dto.setName(e.getEmployee().getFirstName() + " " + e.getEmployee().getLastName());
            });
            if (!appraisalDetailsList.isEmpty()) {
                return ResponseEntity.ok(appraisalDetailsList);
            } else {
                return ResponseEntity.ok(appraisalDetailsList);
            }
        } else {
            throw new EntityNotFoundException("User not found for Employee ID: " + id);
        }
    }

    @Override
    public List<Reward> getRewardDetailsByEmployeeId(Integer id) {
        Optional<User> user = userRepo.findByEmployeeId(id);
        if (user.isPresent()) {
            return rewardDetailsRepository.findByUser_Id(id);
        } else {
            throw new EntityNotFoundException("Employee Not Found");
        }
    }

    @Override
    public String saveProjectRewardDetails(Reward reward) {
        if (!PayrollUtility.validateAmount(reward.getAmount())) {
            throw new EntityNotFoundException("Invalid Amount Details....");
        }
        if (!PayrollUtility.validateType(reward.getRewardType())) {
            throw new EntityNotFoundException("Invalid Reward Type....");
        }
        try {
            Optional<User> userDetails = userRepo.findById(reward.getUser().getId());
            reward.setUser(userDetails.get());
            rewardDetailsRepository.save(reward);
            return "Reward details saved successfully";
        } catch (Exception exception) {
            throw new EntityNotFoundException("Employee not found", exception);
        }
    }

    public ResponseEntity<Object>
    getAllMonthlySalaryDetails() {
        try {
            LocalDate currentDate = LocalDate.now();
            LocalDate previousMonthDate = currentDate.minusMonths(1);
            String previousMonth = previousMonthDate.getMonth().toString();
            int previousYear = previousMonthDate.getYear();

            List<MonthlySalaryDetails> salaryDetails = monthlySalaryDetailsRepo.findByMonth(previousMonth, previousYear);

            if (salaryDetails.isEmpty()) {
                String message = "No salary details found for " + previousMonth + " " + previousYear;
                Map<String, String> message1 = Collections.singletonMap("message", message);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(message1);
            }
            for (MonthlySalaryDetails salaryDetail : salaryDetails) {
                int employeeId = salaryDetail.getEmpId();
                System.out.println("Salary for " + previousMonth + " " + previousYear + ": " + salaryDetail.getCreditedDate());
                User user = userRepo.findById(employeeId).orElse(null);
                if (user != null) {
                    salaryDetail.setEmployee(user);
                }
            }
            return ResponseEntity.ok(salaryDetails);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}