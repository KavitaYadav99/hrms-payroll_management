package com.adt.payroll.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.adt.payroll.model.MonthlySalaryDetails;

import java.util.List;

@Repository
public interface MonthlySalaryDetailsRepo extends JpaRepository<MonthlySalaryDetails, Integer> {
    @Query(value = "SELECT * FROM payroll_schema.monthly_salary_details WHERE emp_id = :empId", nativeQuery = true)
    List<MonthlySalaryDetails> findSalaryDetailsByEmpId(@Param("empId") Integer empId);
}