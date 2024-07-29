package com.adt.payroll.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SalaryDTO {
    private Double adhoc;
    private Double employeePf;
    private Double employerPf;
    private Double employerEsic;
    private Double employeeEsic;
    private Double grossDeduction;
    private Double grossSalary;
    private Double medicalAmount;
    private int empId;
    private String employeeName;
    private String bankName;
    private String accountNo;
    private Double netPay;
    private String month;
    private String year;
    private Double bonus;
    private Double absentDeduction;
    private Double ajdustment;
    private Double basic;
    private Double hra;
    private String comment;
}