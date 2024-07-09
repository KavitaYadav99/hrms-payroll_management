package com.adt.payroll.service;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.adt.payroll.dto.SalaryDetailsDTO;
import com.adt.payroll.exception.NoDataFoundException;
import com.adt.payroll.model.EmpPayrollDetails;
import com.adt.payroll.model.ImageModel;
import com.adt.payroll.model.LeaveBalance;
import com.adt.payroll.model.MonthlySalaryDetails;
import com.adt.payroll.model.PayRecord;
import com.adt.payroll.model.PaySlip;
import com.adt.payroll.model.SalaryDetails;
import com.adt.payroll.model.SalaryModel;
import com.adt.payroll.model.TimeSheetModel;
import com.adt.payroll.model.User;
import com.adt.payroll.repository.EmpPayrollDetailsRepo;
import com.adt.payroll.repository.ImageRepo;
import com.adt.payroll.repository.LeaveBalanceRepository;
import com.adt.payroll.repository.MonthlySalaryDetailsRepo;
import com.adt.payroll.repository.PayRecordRepo;
import com.adt.payroll.repository.SalaryDetailsRepository;
import com.adt.payroll.repository.TimeSheetRepo;
import com.adt.payroll.repository.UserRepo;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.color.Color;
import com.itextpdf.kernel.color.DeviceRgb;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.border.Border;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.property.TextAlignment;
import com.itextpdf.layout.property.VerticalAlignment;
import com.itextpdf.text.DocumentException;

import jakarta.persistence.EntityNotFoundException;

@Service
public class PayRollServiceImpl implements PayRollService {

	private static final Logger log = LogManager.getLogger(PayRollServiceImpl.class);

	@Autowired
	private JavaMailSender javaMailSender;

//    @Value("${spring.mail.username}")
//    private String sender;

	@Autowired
	private MonthlySalaryDetailsRepo monthlySalaryDetailsRepo;

	@Autowired
	private TimeSheetRepo timeSheetRepo;

	@Autowired
	private PayRecordRepo payRecordRepo;
	@Autowired
	private TableDataExtractor dataExtractor;

	@Autowired
	private UserRepo userRepo;
	@Autowired
	private SalaryDetailsRepository salaryDetailsRepo;

	@Autowired
	EmpPayrollDetailsRepo empPayrollDetailsRepo;

	@Autowired
	private ImageRepo imgRepo;
	@Autowired
	private LeaveBalanceRepository leaveBalanceRepo;

	@Value("${holiday}")
	private String[] holiday;

	@Autowired
	Util util;
	
	public String invalidValue="";
	
	public Integer allFieldeValue;

	@Autowired
	private CommonEmailService mailService;

	public PaySlip createPaySlip(int empId, String month, String year) throws ParseException, IOException {
		log.info("inside method");
		String submitDate = "", status = "", employee_id = "";
		String monthYear = month + " " + year;
		int yourWorkingDays = 0, leaves = 0, workDays = 0, saturday = Util.SaturdyaValue, adhoc = 0;
		LocalDate currentdate = LocalDate.now();
		PaySlip paySlip = new PaySlip();
		String sql = "select * from employee_schema.employee_expenses";
		List<Map<String, Object>> tableData = dataExtractor.extractDataFromTable(sql);

		List<String> holidays = Arrays.asList(holiday);
		List<String> lists = new ArrayList<>();

		SimpleDateFormat inputFormat = new SimpleDateFormat("MMMM");
		SimpleDateFormat outputFormat = new SimpleDateFormat("MM"); // 01-12

		Calendar cal = Calendar.getInstance();
		cal.setTime(inputFormat.parse(month));

		Optional<EmpPayrollDetails> empDetails = Optional.ofNullable(empPayrollDetailsRepo.findById(empId)
				.orElseThrow(() -> new NoDataFoundException("employee not found :" + empId)));

		Optional<User> user = Optional.ofNullable(
				userRepo.findById(empId).orElseThrow(() -> new NoDataFoundException("employee not found :" + empId)));
		String name = user.get().getFirstName() + " " + user.get().getLastName();
		List<TimeSheetModel> timeSheetModel = timeSheetRepo.search(empId, month.toUpperCase(), year);

		yourWorkingDays = timeSheetModel.stream()
				.filter(x -> x.getWorkingHour() != null && x.getStatus().equalsIgnoreCase(Util.StatusPresent))
				.collect(Collectors.toList()).size();
		leaves = timeSheetModel.stream().filter(
				x -> x.getWorkingHour() == null && (x.getCheckIn() == null && x.getStatus().equalsIgnoreCase("Leave")))
				.collect(Collectors.toList()).size();

		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy");
		String monthDate = String.valueOf(outputFormat.format(cal.getTime()));

		String firstDayMonth = "01/" + monthDate + "/" + year;
		String lastDayOfMonth = (LocalDate.parse(firstDayMonth, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
				.with(TemporalAdjusters.lastDayOfMonth())).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

		SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
		Date startDate = formatter.parse(firstDayMonth);
		Date endDate = formatter.parse(lastDayOfMonth);

		Calendar start = Calendar.getInstance();
		start.setTime(startDate);
		Calendar end = Calendar.getInstance();
		end.setTime(endDate);

		LocalDate localDate = null;
		while (!start.after(end)) {
			localDate = start.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
			if (start.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY)
				lists.add(localDate.toString());

			start.add(Calendar.DATE, 1);
		}

		lists.removeAll(holidays);
		workDays = lists.size();

		for (Map<String, Object> expense : tableData) {
			String paymentDate = String.valueOf(expense.get("payment_date"));
			paymentDate = paymentDate != null ? paymentDate.trim() : "";
			submitDate = paymentDate.length() >= 5 ? paymentDate.substring(3, 5) : "";
			status = String.valueOf(expense.get("status"));
			employee_id = String.valueOf(expense.get("employee_id"));
			if (submitDate.equals(monthDate) && status.equals("Accepted")
					&& employee_id.equalsIgnoreCase(String.valueOf(empId))) {
				adhoc += Integer.parseInt(String.valueOf(expense.get("expense_amount")));
			}
		}

		float grossSalary = 0.0f;
		if (empDetails.get().getSalary() != null) {
			grossSalary = empDetails.get().getSalary().floatValue();
		}
		int totalWorkingDays = workDays - saturday;
		float amountPerDay = grossSalary / totalWorkingDays;
		float leavePerDay = leaves * amountPerDay;
		float netAmount = (yourWorkingDays * amountPerDay);
		netAmount += adhoc;
		paySlip = new PaySlip(empId, name, empDetails.get().getDesignation(), dtf.format(currentdate),
				empDetails.get().getBankName(), empDetails.get().getAccountNumber(),
				firstDayMonth + " - " + lastDayOfMonth, yourWorkingDays, totalWorkingDays, leaves, leavePerDay,
				grossSalary, netAmount, adhoc);
		ImageModel img = new ImageModel();
		ImageData datas = null;
		if (imgRepo.search() != null) {
			datas = ImageDataFactory.create(imgRepo.search());
		} else {
			datas = Util.getImage();
		}

		log.info("image path set");
		Image alpha = new Image(datas);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PdfWriter pdfWriter = new PdfWriter(baos);
		PdfDocument pdfDocument = new PdfDocument(pdfWriter);
		Document document = new Document(pdfDocument);

		pdfDocument.setDefaultPageSize(PageSize.A4);
		float col = 250f;
		float columnWidth[] = { col, col };
		Table table = new Table(columnWidth);
		table.setBackgroundColor(new DeviceRgb(63, 169, 219)).setFontColor(Color.WHITE);
		table.addCell(new Cell().add("Pay Slip").setTextAlignment(TextAlignment.CENTER)
				.setVerticalAlignment(VerticalAlignment.MIDDLE).setMarginTop(30f).setMarginBottom(30f).setFontSize(30f)
				.setBorder(Border.NO_BORDER));
		table.addCell(new Cell().add(Util.ADDRESS).setTextAlignment(TextAlignment.RIGHT).setMarginTop(30f)
				.setMarginBottom(30f).setBorder(Border.NO_BORDER).setMarginRight(10f));
		float colWidth[] = { 150, 150, 100, 100 };
		Table employeeTable = new Table(colWidth);
		employeeTable.addCell(new Cell(0, 4).add(Util.EmployeeInformation).setBold());
		employeeTable.addCell(new Cell().add(Util.EmployeeNumber).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(String.valueOf(user.get().getId())).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.Date).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(dtf.format(currentdate)).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.Name).setBorder(Border.NO_BORDER));
		employeeTable.addCell(
				new Cell().add(user.get().getFirstName() + " " + user.get().getLastName()).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.BankName).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(empDetails.get().getBankName()).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.JobTitle).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(empDetails.get().getDesignation()).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.AccountNumber).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(empDetails.get().getAccountNumber()).setBorder(Border.NO_BORDER));

		Table itemInfo = new Table(columnWidth);
		itemInfo.addCell(new Cell().add(Util.PayPeriods));
		itemInfo.addCell(new Cell().add(firstDayMonth + " - " + lastDayOfMonth));
		itemInfo.addCell(new Cell().add(Util.YourWorkingDays));
		itemInfo.addCell(new Cell().add(String.valueOf(yourWorkingDays)));
		itemInfo.addCell(new Cell().add(Util.TotalWorkingDays));
		itemInfo.addCell(new Cell().add(String.valueOf(totalWorkingDays)));
		itemInfo.addCell(new Cell().add("Adhoc Amount"));
		itemInfo.addCell(new Cell().add(String.valueOf(adhoc)));
		itemInfo.addCell(new Cell().add(Util.NumberOfLeavesTaken));
		itemInfo.addCell(new Cell().add(String.valueOf(leaves)));
		itemInfo.addCell(new Cell().add(Util.GrossSalary));
		itemInfo.addCell(new Cell().add(String.valueOf(grossSalary)));
		itemInfo.addCell(new Cell().add(Util.NetAmountPayable));
		itemInfo.addCell(new Cell().add(String.valueOf(netAmount)));
		document.add(alpha);

		document.add(table);
		document.add(new Paragraph("\n"));
		document.add(employeeTable);
		document.add(itemInfo);
		document.add(
				new Paragraph("\n(Note - This is a computer generated statement and does not require a signature.)")
						.setTextAlignment(TextAlignment.CENTER));
		document.close();
		log.warn("Successfully");

		// sendEmail(baos, name, user.get().getEmail(), monthYear);

		mailService.sendEmail(baos, name, user.get().getEmail(), monthYear);

		return paySlip;
	}

	// Excel Pay Slip

	public String generatePaySlip(MultipartFile file ,String email) throws IOException, ParseException {
		DateTimeZone istTimeZone = DateTimeZone.forID("Asia/Kolkata");
        DateTime currentDateTime = new DateTime(istTimeZone);

        Timestamp lastUpdatedDate = monthlySalaryDetailsRepo.findLatestSalaryUpdatedDate();

        if (lastUpdatedDate != null) {
            DateTime lastUpdatedDateTime = new DateTime(lastUpdatedDate.getTime(), istTimeZone);

            Duration duration = new Duration(lastUpdatedDateTime, currentDateTime);
            long minutes = duration.getStandardMinutes();
	            if (minutes <= 10) {
	                return "You have generated the payslip "+minutes+" mins ago. Please try again after 10 mins.";
	            }
	        }
		 
		String empId = "", name = "", salary = "", esic = "", pf = "", paidLeave = "", bankName = "",
				accountNumber = "", gmail = "", designation = "", submitDate = "", status = "", employee_id = "",
				joiningDate = "";
		String sheetName = "";
		int adjustment = 0, tds = 0, adhoc1 = 0, medicalInsurance = 0, adhoc3 = 0, workingDays = 0, present = 0,
				leave = 0, halfDay = 0, limit = 30;
		Map<String, Integer> excelColumnName = new HashMap<>();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		SimpleDateFormat inputFormat = new SimpleDateFormat("MMMM");
		SimpleDateFormat outputFormat = new SimpleDateFormat("MM");
		NumberFormat format = NumberFormat.getInstance();
		String projDir = System.getProperty("user.dir");
		XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream());
		DataFormatter dataFormatter = new DataFormatter();

		for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
			XSSFSheet sh = workbook.getSheetAt(i);
			if (sh.getLastRowNum() > 0) {
				sheetName = sh.getSheetName();
			}
		}

		XSSFSheet sheet = workbook.getSheet(sheetName);

		Row headerRow = sheet.getRow(0);

		int columnCount = headerRow.getLastCellNum();
		String columnHeader = "";
		for (int i = 0; i < columnCount; i++) {
			headerRow.getCell(i);
			headerRow.getCell(i).getStringCellValue();
			columnHeader = String.valueOf(headerRow.getCell(i)).trim();

			excelColumnName.put(columnHeader, i);
		}

		LocalDate currentdate = LocalDate.now();
		LocalDate earlier = currentdate.minusMonths(1);

		Calendar cal = Calendar.getInstance();
		cal.setTime(inputFormat.parse(String.valueOf(earlier.getMonth())));

		String monthDate = String.valueOf(outputFormat.format(cal.getTime()));
//
//		String monthYear = String.valueOf(earlier.getMonth() + " " + earlier.getYear());
//		String firstDayMonth = "01/" + monthDate + "/" + +earlier.getYear();
//		String lastDayOfMonth = (LocalDate.parse(firstDayMonth, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
//				.with(TemporalAdjusters.lastDayOfMonth())).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
//		String payPeriod = firstDayMonth + " - " + lastDayOfMonth;
		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy");
		String date = dtf.format(currentdate);
		String sql = "select * from employee_schema.employee_expenses";
		List<Map<String, Object>> tableData = dataExtractor.extractDataFromTable(sql);
		List<User> employee = userRepo.findAll();
		Map<String, String> paySlipDetails = util.getWorkingDaysAndMonth();
		//int workingDay = util.getWorkingDays();
		for (int i = 2; i <= sheet.getLastRowNum(); i++) {

			try {
				XSSFRow row = sheet.getRow(i);
				try {
				
					
					if(isNotNull(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.EmployeeNumber))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Name))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.TotalWorkingDays))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.YourWorkingDays))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Leave))),
					(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.HalfDay)))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.salary))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.PaidLeave))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.BankName))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.AccountNumber))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.DESIGNATION))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Gmail))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.JoiningDate))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Esic))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.PF))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.ADJUSTMENT))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.TDS))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.MEDICAL_INSURANCE))),
					dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Adhoc1))), paySlipDetails)) {
						if(allFieldeValue<19) {
							mailService.sendEmail(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Name))), invalidValue);
							continue;	
						}else {
							continue;
						}
						
					}
					
					
					
					empId = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.EmployeeNumber)));
					name = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Name)));
					workingDays = Integer.parseInt(
							dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.TotalWorkingDays))));
					present = Integer.parseInt(
							dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.YourWorkingDays))));
					leave = Integer
							.parseInt(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Leave))));
					halfDay = Integer
							.parseInt(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.HalfDay))));
					salary = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.salary)));
					paidLeave = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.PaidLeave)));
					bankName = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.BankName)));
					accountNumber = format
							.format(row.getCell(excelColumnName.get(Util.AccountNumber)).getNumericCellValue())
							.replace(",", "");
					designation = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.DESIGNATION)));
					gmail = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Gmail)));
					joiningDate = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.JoiningDate)));
					esic = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Esic)));
					pf = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.PF)));

					try {
						adjustment = Integer.parseInt(
								dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.ADJUSTMENT))));
					} catch (NumberFormatException e) {
						adjustment = 0;
					}
					try {
						tds = Integer
								.parseInt(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.TDS))));
					} catch (NumberFormatException e) {
						tds = 0;
					}
					try {
						medicalInsurance = Integer.parseInt(dataFormatter
								.formatCellValue(row.getCell(excelColumnName.get(Util.MEDICAL_INSURANCE))));
					} catch (NumberFormatException e) {
						medicalInsurance = 0;
					}

					try {
						adhoc1 = Integer
								.parseInt(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Adhoc1))));
					} catch (NumberFormatException e) {
						adhoc1 = 0;
					}
					String[] fullName = name.split(" ");
					String fName = fullName[0].toString();
					String lName = fullName[1].toString();
					if (halfDay > limit || leave > limit || workingDays > limit || present > limit) {
						continue;
					}
					for (Map<String, Object> expense : tableData) {
						submitDate = String.valueOf(expense.get("payment_date")).substring(3, 5);
						status = String.valueOf(expense.get("status"));
						employee_id = String.valueOf(expense.get("employee_id"));
						if (submitDate.equals(monthDate) && status.equals("Accepted")
								&& employee_id.equalsIgnoreCase(String.valueOf(empId))) {
							adhoc1 += Integer.parseInt(String.valueOf(expense.get("expense_amount")));
						}
					}
				
					MonthlySalaryDetails monthlySalaryDetails =new  MonthlySalaryDetails();
					
					
					if (checkEmpDetails(empId, gmail, accountNumber, employee, fName, lName)) {
						log.info("Getting error while validating the field", invalidValue);
						mailService.sendEmail(name,invalidValue);
						continue;

					}
					log.info("Generating Pdf");
					baos = createPdf(empId, name, workingDays, present, leave, halfDay, salary, paidLeave, date,
							bankName, accountNumber, designation, joiningDate, adhoc1, paySlipDetails.get(Util.PAY_PERIOD), esic, pf,
							adjustment, medicalInsurance, tds, monthlySalaryDetails);

					log.info("Pdf generated successfully.");
					   
					SimpleDateFormat f = new SimpleDateFormat("dd-MM-yyyy");
					Calendar cal1 = Calendar.getInstance();
					String date1 = f.format(cal1.getTime());
					cal1.add(Calendar.MONTH, -1);
					SimpleDateFormat monthFormat = new SimpleDateFormat("MMM");
				    String monthName = monthFormat.format(cal1.getTime()).toUpperCase();
				    DateTime current = new DateTime(istTimeZone);
			       // Timestamp currentTime = new Timestamp(current.getMillis());
					double medical=medicalInsurance;
					double adhoc=adhoc1;
					double adj =adjustment;	
					if(email==null||email.isEmpty()) {
					monthlySalaryDetails.setEmpId(Integer.parseInt(empId));
					monthlySalaryDetails.setMedicalInsurance(medical);
					monthlySalaryDetails.setAdhoc(adhoc);
					monthlySalaryDetails.setAdjustment(adj);
					monthlySalaryDetails.setDearnessAllowance(0.0);
					monthlySalaryDetails.setCreditedDate(date1);
					monthlySalaryDetails.setMonth(monthName);
					monthlySalaryDetails.setTotalWorkingDays(workingDays);
					monthlySalaryDetails.setPaidLeave(Integer.parseInt(paidLeave));
					monthlySalaryDetails.setHalfDay(halfDay);
					monthlySalaryDetails.setPresentDays(present);
					monthlySalaryDetails.setBonus(0.0);
					monthlySalaryDetails.setUpdatedWhen(new Timestamp(current.getMillis()));
					//monthlySalaryDetails.setUpdatedWhen(Timestamp.valueOf(currentZonedDateTime.toLocalDateTime()));
					monthlySalaryDetailsRepo.save(monthlySalaryDetails);
					}
					if(email!=null&&!email.isEmpty())
					gmail=email; 
					
					mailService.sendEmail(baos, name, gmail, paySlipDetails.get(Util.MONTH)+" "+paySlipDetails.get(Util.YEAR));
					log.info("Mail send successfully to the employee.");
				} catch (Exception e) {
					log.info("Getting error while payslip generation.", e.getMessage());
					mailService.sendEmail(name);
					continue;
				}
			} catch (Exception e) {
				break;
			}
			}
	
		return "Mail Send Successfully";
	}

	public ByteArrayOutputStream createPdf(String empId, String name, int totalWorkingDays, int present, int leave,
			int halfDay, String salary, String paidLeave, String date, String bankName, String accountNumber,
			String designation, String joiningDate, int adhoc1, String payPeriod, String esic, String pf,
			int adjustment, int medicalInsurance, int tds, MonthlySalaryDetails monthlySalaryDetails ) throws IOException, DocumentException {

		float pfAmount = 0;
		double grossSalary = Double.parseDouble(salary);
		double employerPf =  (double) (Math.round(((grossSalary / 2) * 0.13)));
		double employeeESICAmount = 0;
		float employerESICAmount  = 0;

		if (esic.equalsIgnoreCase("Yes") && pf.equalsIgnoreCase("Yes")) {
			employeeESICAmount = Double.valueOf(Math.round(grossSalary * (0.0325)));
			employerESICAmount  = (float) (Math.round(grossSalary * (0.0075)));
			grossSalary = Math
					.round(grossSalary - employerPf - (employeeESICAmount + employerESICAmount) + (grossSalary * 0.01617));
		} else if (esic.equalsIgnoreCase("No") && pf.equalsIgnoreCase("Yes")) {

			grossSalary = Math.round(grossSalary - employerPf + (grossSalary * 0.01617));
		}
		
		double basic = Math.round(grossSalary / 2);
		double hra = Math.round(grossSalary / 2);
		int yourWorkingDays = present + Integer.parseInt(paidLeave);
		double amountPerDay = grossSalary / totalWorkingDays;
		double unpaidLeave = totalWorkingDays - present;
		monthlySalaryDetails.setAbsentDays((int)unpaidLeave);
		unpaidLeave -= Integer.parseInt(paidLeave);
		unpaidLeave *= amountPerDay;
		double HalfDays = halfDay * amountPerDay / 2;
		double netAmount = Math.round((yourWorkingDays * amountPerDay) - HalfDays);
		netAmount = Math.round(netAmount + adhoc1);
		if (netAmount < 0) {
			netAmount = 0;
			adhoc1 = 0;
		}

//		if (esic.equalsIgnoreCase("yes") && netAmount != 0) {
//			employerESICAmount = (float) (Math.round(grossSalary * (0.0075)));
//
//		}

		if (pf.equalsIgnoreCase("yes") && netAmount != 0) {
			pfAmount = (float) (Math.round(basic * 0.120));
		}
		double halfDayAmount = ((double) halfDay / 2) * amountPerDay;
//		double grossDeduction = employerESICAmount + pfAmount + (unpaidLeave - halfDayAmount) + adjustment + medicalInsurance
//				+ tds;
		double grossDeduction = employeeESICAmount + pfAmount + (unpaidLeave - halfDayAmount) + adjustment + medicalInsurance
				+ tds;
		double employerEsic =employerESICAmount;
		double employeePf=pfAmount;
		double td=tds;
		netAmount -= employeeESICAmount;
		netAmount -= pfAmount;
		netAmount = Math.round(netAmount);
		netAmount -= medicalInsurance;
		netAmount -= adjustment;
		monthlySalaryDetails.setHouseRentAllowance(hra);
		monthlySalaryDetails.setBasic(basic);
		monthlySalaryDetails.setGrossSalary(grossSalary);
		monthlySalaryDetails.setGrossDeduction(grossDeduction);
		monthlySalaryDetails.setEmployerESICAmount(employerEsic);
		monthlySalaryDetails.setEmployeeESICAmount(employeeESICAmount);
		monthlySalaryDetails.setEmployeePFAmount(employeePf);
		monthlySalaryDetails.setEmployerPFAmount(employerPf);

		monthlySalaryDetails.setUnpaidLeave((int)unpaidLeave);
		monthlySalaryDetails.setNetSalary(netAmount);
		monthlySalaryDetails.setTds(td);
		ByteArrayOutputStream byteArrayOutputStream = DetailedSalarySlip.builder().build()
				.generateDetailedSalarySlipPDF(empId, name, totalWorkingDays, present, leave, halfDay, salary,
						paidLeave, date, bankName, accountNumber, designation, joiningDate, adhoc1, payPeriod,
						employeeESICAmount, pfAmount, netAmount, grossSalary, basic, hra, amountPerDay, unpaidLeave, adjustment,
						medicalInsurance, tds);
		return byteArrayOutputStream;
	}

	@Override
	public byte[] viewPay(SalaryModel salaryModel, String month, String year)
			throws ParseException, UnsupportedEncodingException {
		log.info("inside method");
		int empId = salaryModel.getEmpId();
		List<PayRecord> payRecordList = payRecordRepo.findByEmpId(empId);

		for (PayRecord payRecord : payRecordList) {
			if (payRecord != null) {
				if (payRecord.getEmpId() == empId && payRecord.getMonth().equalsIgnoreCase(month)
						&& payRecord.getYear().equalsIgnoreCase(year))
					return payRecord.getPdf();
			}
		}

		String submitDate = "", status = "", employee_id = "";
		String monthYear = month + " " + year;
		int yourWorkingDays = 0, leaves = 0, workDays = 0, saturday = Util.SaturdyaValue, adhoc = 0;
		LocalDate currentdate = LocalDate.now();

		String sql = "select * from employee_schema.employee_expenses";
		List<Map<String, Object>> tableData = dataExtractor.extractDataFromTable(sql);

		PayRecord payRecord = new PayRecord();
		payRecord.setEmpId(empId);
		payRecord.setMonth(month);
		payRecord.setEmpName(salaryModel.getEmpName());
		payRecord.setYear(year);

		List<String> holidays = Arrays.asList(holiday);
		List<String> lists = new ArrayList<>();

		SimpleDateFormat inputFormat = new SimpleDateFormat("MMMM");
		SimpleDateFormat outputFormat = new SimpleDateFormat("MM"); // 01-12

		Calendar cal = Calendar.getInstance();
		cal.setTime(inputFormat.parse(month));

		Optional<User> user = Optional.ofNullable(userRepo.findById(empId)
				.orElseThrow(() -> new EntityNotFoundException("employee not found :" + empId)));
		String name = salaryModel.getEmpName();
		List<TimeSheetModel> timeSheetModel = timeSheetRepo.search(empId, month.toUpperCase(), year);

		yourWorkingDays = timeSheetModel.stream()
				.filter(x -> x.getWorkingHour() != null && x.getStatus().equalsIgnoreCase(Util.StatusPresent))
				.collect(Collectors.toList()).size();
		leaves = timeSheetModel.stream().filter(
				x -> x.getWorkingHour() == null && (x.getCheckIn() == null && x.getStatus().equalsIgnoreCase("Leave")))
				.collect(Collectors.toList()).size();

		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy");
		String monthDate = String.valueOf(outputFormat.format(cal.getTime()));

		String firstDayMonth = "01/" + monthDate + "/" + year;
		String lastDayOfMonth = (LocalDate.parse(firstDayMonth, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
				.with(TemporalAdjusters.lastDayOfMonth())).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

		SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy");
		Date startDate = formatter.parse(firstDayMonth);
		Date endDate = formatter.parse(lastDayOfMonth);

		Calendar start = Calendar.getInstance();
		start.setTime(startDate);
		Calendar end = Calendar.getInstance();
		end.setTime(endDate);

		LocalDate localDate = null;
		while (!start.after(end)) {
			localDate = start.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
			if (start.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY)
				lists.add(localDate.toString());

			start.add(Calendar.DATE, 1);
		}

		lists.removeAll(holidays);
		workDays = lists.size();

		for (Map<String, Object> expense : tableData) {
			submitDate = String.valueOf(expense.get("payment_date")).substring(3, 5);
			status = String.valueOf(expense.get("status"));
			employee_id = String.valueOf(expense.get("employee_id"));
			if (submitDate.equals(monthDate) && status.equals("Accepted")
					&& employee_id.equalsIgnoreCase(String.valueOf(empId))) {
				adhoc += Integer.parseInt(String.valueOf(expense.get("expense_amount")));
			}
		}

		float grossSalary = salaryModel.getSalary();
		int totalWorkingDays = workDays - saturday;
		float amountPerDay = grossSalary / totalWorkingDays;
		float leavePerDay = leaves * amountPerDay;
		float netAmount = (yourWorkingDays * amountPerDay);
		netAmount += adhoc;

		ImageModel img = new ImageModel();

		ImageData datas = ImageDataFactory.create(imgRepo.search());
		log.info("image path set");
		Image alpha = new Image(datas);
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		PdfWriter pdfWriter = new PdfWriter(baos);
		PdfDocument pdfDocument = new PdfDocument(pdfWriter);
		Document document = new Document(pdfDocument);

		pdfDocument.setDefaultPageSize(PageSize.A4);
		float col = 250f;
		float columnWidth[] = { col, col };
		Table table = new Table(columnWidth);
		table.setBackgroundColor(new DeviceRgb(63, 169, 219)).setFontColor(Color.WHITE);
		table.addCell(new Cell().add("Pay Slip").setTextAlignment(TextAlignment.CENTER)
				.setVerticalAlignment(VerticalAlignment.MIDDLE).setMarginTop(30f).setMarginBottom(30f).setFontSize(30f)
				.setBorder(Border.NO_BORDER));
		table.addCell(new Cell().add(Util.ADDRESS).setTextAlignment(TextAlignment.RIGHT).setMarginTop(30f)
				.setMarginBottom(30f).setBorder(Border.NO_BORDER).setMarginRight(10f));
		float colWidth[] = { 125, 150, 125, 100 };
		Table employeeTable = new Table(colWidth);
		employeeTable.addCell(new Cell(0, 4).add(
				Util.EmployeeInformation + "                                                                          "
						+ "Date : " + dtf.format(currentdate))
				.setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.EmployeeNumber).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(String.valueOf(empId)).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.JoiningDate).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(salaryModel.getJoinDate()).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.Name).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(salaryModel.getEmpName()).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.BankName).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(salaryModel.getBankName()).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.JobTitle).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(salaryModel.getRole()).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(Util.AccountNumber).setBorder(Border.NO_BORDER));
		employeeTable.addCell(new Cell().add(salaryModel.getAccountNumber()).setBorder(Border.NO_BORDER));

		Table itemInfo = new Table(columnWidth);
		itemInfo.addCell(new Cell().add(Util.PayPeriods));
		itemInfo.addCell(new Cell().add(firstDayMonth + " - " + lastDayOfMonth));
		itemInfo.addCell(new Cell().add(Util.YourWorkingDays));
		itemInfo.addCell(new Cell().add(String.valueOf(yourWorkingDays)));
		itemInfo.addCell(new Cell().add(Util.TotalWorkingDays));
		itemInfo.addCell(new Cell().add(String.valueOf(totalWorkingDays)));
		itemInfo.addCell(new Cell().add("Adhoc Amount"));
		itemInfo.addCell(new Cell().add(String.valueOf(adhoc)));
		itemInfo.addCell(new Cell().add(Util.NumberOfLeavesTaken));
		itemInfo.addCell(new Cell().add(String.valueOf(leaves)));
		itemInfo.addCell(new Cell().add(Util.GrossSalary));
		itemInfo.addCell(new Cell().add(String.valueOf(grossSalary)));
		itemInfo.addCell(new Cell().add(Util.NetAmountPayable));
		itemInfo.addCell(new Cell().add(String.valueOf(netAmount)));
		document.add(alpha);

		document.add(table);
		document.add(new Paragraph("\n"));
		document.add(employeeTable);
		document.add(itemInfo);
		document.add(
				new Paragraph("\n(Note - This is a computer generated statement and does not require a signature.)")
						.setTextAlignment(TextAlignment.CENTER));
		document.close();
		log.warn("Successfully");
		payRecord.setPdf(baos.toByteArray());
		payRecordRepo.save(payRecord);

		return baos.toByteArray();
	}

	@Override
	public String updateNetAmountInExcel(MultipartFile file) throws IOException {

		String salary = "", paidLeave = "", sheetName = "";
		int NetAmount = 0, adhoc1 = 0, adhoc2 = 0, adhoc3 = 0, workingDays = 0, present = 0, halfDay = 0;

		Map<String, Integer> excelColumnName = new HashMap<>();
		String projDir = System.getProperty("user.dir");
		NumberFormat format = NumberFormat.getInstance();
		XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream());
		DataFormatter dataFormatter = new DataFormatter();

		for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
			XSSFSheet sh = workbook.getSheetAt(i);
			if (sh.getLastRowNum() > 0) {
				sheetName = sh.getSheetName();
			}
		}

		XSSFSheet sheet = workbook.getSheet(sheetName);

		Row headerRow = sheet.getRow(0);

		int columnCount = headerRow.getLastCellNum();
		String columnHeader = "";
		for (int i = 0; i < columnCount; i++) {
			headerRow.getCell(i);
			headerRow.getCell(i).getStringCellValue();
			columnHeader = String.valueOf(headerRow.getCell(i)).trim();

			excelColumnName.put(columnHeader, i);
		}
		for (int i = 2; i <= 50; i++) {
			try {
				XSSFRow row = sheet.getRow(i);
				try {
					workingDays = Integer.parseInt(
							dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.TotalWorkingDays))));
					present = Integer.parseInt(
							dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.YourWorkingDays))));
					halfDay = Integer
							.parseInt(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.HalfDay))));
					salary = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.salary)));
					paidLeave = dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.PaidLeave)));

					adhoc1 = Integer
							.parseInt(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Adhoc1))));
					adhoc2 = Integer
							.parseInt(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Adhoc2))));
					adhoc3 = Integer
							.parseInt(dataFormatter.formatCellValue(row.getCell(excelColumnName.get(Util.Adhoc3))));
					double netAmount = calculateNetAmount(workingDays, present, salary, paidLeave, halfDay, adhoc1,
							adhoc2, adhoc3);
					row.createCell(excelColumnName.get(Util.NetAmount)).setCellValue(netAmount);

				} catch (Exception e) {
					continue;
				}
			} catch (Exception e) {
				break;
			}
		}
		FileOutputStream fileOutputStream = new FileOutputStream(
				"C:/Users/hp/Desktop/excel/" + file.getOriginalFilename());
		workbook.write(fileOutputStream);
		fileOutputStream.close();

		return "done";
	}

	public float calculateNetAmount(int totalWorkingDays, int present, String salary, String paidLeave, int halfDay,
			int adhoc1, int adhoc2, int adhoc3) {
		float grossSalary = Float.valueOf(salary);
		int yourWorkingDays = present + Integer.parseInt(paidLeave);
		float amountPerDay = grossSalary / totalWorkingDays;
		float HalfDays = halfDay * amountPerDay / 2;
		float netAmount = (yourWorkingDays * amountPerDay) - HalfDays;
		netAmount = netAmount + adhoc1 + adhoc2 + adhoc3;
		return netAmount;
	}

	public boolean checkEmpDetails(String empId, String gmail, String accountNumber, List<User> employees, String fname,
			String lName) {
		log.info("validating the columns value gmail{},  accountNumber{}, fname{}, lName{}",gmail, accountNumber, fname, lName );
		int userId = Integer.parseInt(empId);
		boolean flag = true;
		Optional<User> employee = employees.stream().filter(user -> user.getId() == userId).findFirst();
		if (employee != null && !employee.isEmpty()) {
			String[] fullName = employee.get().getLastName().split(" ");
			String lname = fullName[0].toString();
			if (employee.get().getEmail().trim().equalsIgnoreCase(gmail)
					&& employee.get().getFirstName().trim().equalsIgnoreCase(fname)
					&& lname.trim().trim().equalsIgnoreCase(lName)) {
				EmpPayrollDetails empDetails = empPayrollDetailsRepo.getByEmpId(employee.get().getId());
				if (empDetails.getAccountNumber().equalsIgnoreCase(accountNumber)) {
					flag = false;
					return flag;
				}
				invalidValue="please enter currect Account Number";
				return flag;
				
			}
			invalidValue="please enter correct Email, First name and Last name";	
		}

		return flag;
	}
	
	
	public boolean isNotNull(String empId, String name, String workingDays, String presentWorkingDays, 
			String leave, String halfDay,String salary,String paidLeave, String bankName, String accountNumber, String designation,String email, String joiningDate,
			String esic,String pfAmount,String adjustment,String tds,String medicalInsurance,String adhoc, Map<String, String> paySlipDetails) {
		log.info("Verifying columns ");
		int	totalDays =Integer.parseInt(paySlipDetails.get(Util.WORKING_DAY));
		
		invalidValue = "] fields are missing or null. Kindy fill correct information !!";
		
		allFieldeValue=0;
		if (empId.isEmpty() || empId == null) {
			invalidValue = ",employeeId " + invalidValue;
			allFieldeValue++;

		}
		if (name.isEmpty() || name == null) {
			invalidValue = ",name" + invalidValue;
			allFieldeValue++;

		}
		if (workingDays.isEmpty() || workingDays == null || Integer.parseInt(workingDays) >totalDays || Integer.parseInt(workingDays) != totalDays) {
			invalidValue = ",workingDay" + invalidValue;
			allFieldeValue++;
		}

		if (presentWorkingDays.isEmpty() || presentWorkingDays == null) {
			invalidValue = ",presentDay" + invalidValue;
			allFieldeValue++;

		}

		if (leave.isEmpty() || leave == null) {
			invalidValue = ",leave" + invalidValue;
			allFieldeValue++;
		}
		if (halfDay.isEmpty() || halfDay == null) {
			invalidValue = ",halfDay" + invalidValue;
			allFieldeValue++;
		}

		if (salary.isEmpty() || salary == null) {
			invalidValue = ",salary" + invalidValue;
			allFieldeValue++;
		}
		if (paidLeave.isEmpty() || paidLeave == null) {
			invalidValue = ",paidLeave" + invalidValue;
			allFieldeValue++;
		}

		if (bankName.isEmpty() || bankName == null) {
			invalidValue = ",bankName" + invalidValue;
			allFieldeValue++;
		}
		if (accountNumber.isEmpty() || accountNumber == null) {
			invalidValue = ",accountNumber" + invalidValue;
			allFieldeValue++;
		}

		if (designation.isEmpty() || designation == null) {
			invalidValue = ",designation" + invalidValue;
			allFieldeValue++;
		}
		if (email.isEmpty() || email == null) {
			invalidValue = ",email" + invalidValue;
			allFieldeValue++;
		}

		if (joiningDate.isEmpty() || joiningDate == null) {
			invalidValue = ",joiningDate " + invalidValue;
			allFieldeValue++;
		}

		if (esic.isEmpty() || esic == null) {
			invalidValue = ",esic " + invalidValue;
			allFieldeValue++;
		}

		if (pfAmount.isEmpty() || pfAmount == null) {
			invalidValue = ",pfAmount " + invalidValue;
			allFieldeValue++;
		}

		if (adjustment.isEmpty() || adjustment == null) {
			invalidValue = ",adjustment " + invalidValue;
			allFieldeValue++;
		}

		if (tds.isEmpty() || tds == null) {

			invalidValue = ",tds " + invalidValue;
			allFieldeValue++;

		}
		if (medicalInsurance.isEmpty() || medicalInsurance == null) {
			invalidValue = ",medicalInsurance " + invalidValue;
			allFieldeValue++;
		}

		if (adhoc.isEmpty() || adhoc == null) {
			invalidValue = ",adhoc " + invalidValue;
			allFieldeValue++;
		}

		if (!invalidValue.equalsIgnoreCase("] fields are missing or null. Kindy fill correct information !!")) {
			invalidValue = invalidValue.substring(1);
			invalidValue = "Given [" + invalidValue;
			log.error("Error found ", invalidValue);
			return true;
		}
		return false;
	}

//  generate salary code modification
	@Override
	public String generatePaySlipForAllEmployees(String emailInput) throws ParseException, IOException {

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		// int officeTotalWorkingDay = util.getWorkingDays();
		Map<String, String> paySlipDetails = util.getWorkingDaysAndMonth();
		List<SalaryDetails> salaryDetailsList = salaryDetailsRepo.findAll();
		String name = null;
		PaySlip paySlip = null;
		Map<ByteArrayOutputStream, String> payslip = new HashMap<>();
		if ((!salaryDetailsList.isEmpty()) || (salaryDetailsList.size() > 0)) {

			for (SalaryDetails salaryDetails : salaryDetailsList) {
				try {
					if (salaryDetails.getEmpId() != 0) {
						try {
							if (userRepo.existsById(salaryDetails.getEmpId())) {
								paySlip = new PaySlip();
								Optional<User> userOptional = userRepo.findByEmployeeId(salaryDetails.getEmpId());
								Optional<EmpPayrollDetails> empPayrollDetailsOptional = empPayrollDetailsRepo
										.findByEmployeeId(salaryDetails.getEmpId());
								if (!empPayrollDetailsOptional.isPresent()) {
									log.info(
											"Employee payroll details are not present. Please enter the employee record "
													+ salaryDetails.getEmpId());
									continue;
								}
								String fName = userOptional.get().getFirstName();
								String lName = userOptional.get().getLastName();
								name = fName + " " + lName;
								String gmail = userOptional.get().getEmail();

								// null checks
								if (isNotNull(salaryDetails.getEmpId(), name, userOptional.get().getEmail(),
										salaryDetails.getBasic(), salaryDetails.getGrossSalary(),
										salaryDetails.getHouseRentAllowance(), salaryDetails.getEmployerPFAmount(),
										salaryDetails.getEmployeePFAmount(),
										empPayrollDetailsOptional.get().getAccountNumber(),
										empPayrollDetailsOptional.get().getBankName(),
										empPayrollDetailsOptional.get().getDesignation(),
										empPayrollDetailsOptional.get().getJoinDate(),
										Integer.parseInt(paySlipDetails.get("workingDays")))) {

									double salary = empPayrollDetailsOptional.get().getSalary();
									boolean isESIC = false;
									if (salary <= 21000) {
										isESIC = true;
									}

									double calculatedGross = grossSalaryCalculation(empPayrollDetailsOptional.get(),
											salaryDetails.getBasic(), salaryDetails, isESIC, name);
									if (calculatedGross == -1) {
										continue;
									}
									double empGrossSalaryAmount = salaryDetails.getGrossSalary();
									double grossSalaryDifference = Math.abs(calculatedGross - empGrossSalaryAmount);

									if (grossSalaryDifference > 100) {
										log.info(
												"GrossSalaryAmount {} different btw calculatedGross {} &  empGrossSalaryAmount {} respectively"
														+ "/-, Please check & correct Amount for the employee ",
												grossSalaryDifference, calculatedGross, empGrossSalaryAmount,
												salaryDetails.getEmpId());
										mailService.sendEmail(name,
												"GrossSalaryAmount different " + grossSalaryDifference
														+ " of calculatedGross and fetched empGrossSalaryAmount "
														+ calculatedGross + " , " + empGrossSalaryAmount
														+ "Please check & correct Amount for the employee "
														+ salaryDetails.getEmpId());
										continue;
									}

									double totalLeaveDeduction = calculateAndUpdateEmployeeTotalLeaves(
											salaryDetails.getEmpId(), empGrossSalaryAmount,
											paySlipDetails.get(Util.MONTH), paySlipDetails.get(Util.YEAR),
											Integer.parseInt(paySlipDetails.get(Util.WORKING_DAY)), paySlip);

									if (totalLeaveDeduction == -1) {
										log.info("Employees leave balance record is not exist. please enter the data.",
												salaryDetails.getEmpId());
										continue;
									}

									double empNetSalaryAmount = Math
											.round(empGrossSalaryAmount - (salaryDetails.getEmployeeESICAmount()
													+ salaryDetails.getEmployeePFAmount()
													+ salaryDetails.getMedicalInsurance()));
									// empNetSalaryAmount = Math.round(empNetSalaryAmount + adhoc);
									empNetSalaryAmount = Math.round(empNetSalaryAmount - totalLeaveDeduction);

									if (empNetSalaryAmount < 0) {
										empNetSalaryAmount = 0;
									}
									Double grossEarning = salaryDetails.getGrossSalary();
									Double grossDeductionCal = salaryDetails.getEmployeeESICAmount()
											+ salaryDetails.getEmployeePFAmount() + paySlip.getLeaveDeductionAmount()
											+ salaryDetails.getMedicalInsurance();
									double grossDeduction = Math.round(grossDeductionCal) <= Math.round(grossEarning)
											? Math.round(grossDeductionCal)
											: Math.round(grossEarning);

									paySlip.setGrossSalary(grossEarning.floatValue());
									paySlip.setAccountNumber(empPayrollDetailsOptional.get().getAccountNumber());
									paySlip.setBankName(empPayrollDetailsOptional.get().getBankName());
									paySlip.setJobTitle(empPayrollDetailsOptional.get().getDesignation());
									paySlip.setName(name);
									paySlip.setTotalWorkingDays(Integer.parseInt(paySlipDetails.get(Util.WORKING_DAY)));
									paySlip.setPayPeriods(paySlipDetails.get(Util.PAY_PERIOD));
									paySlip.setNetSalaryAmount(empNetSalaryAmount);
									paySlip.setSalary(empPayrollDetailsOptional.get().getSalary());
									paySlip.setGrossDeduction(grossDeduction);

									baos = DetailedSalarySlip.builder().build().generateDetailedSalarySlipPDF(
											salaryDetails, paySlip, empPayrollDetailsOptional.get().getJoinDate(),
											paySlipDetails.get(Util.MONTH), 0);

									if (!emailInput.isEmpty() && !emailInput.isBlank()) {
										payslip.put(baos, name);
									}

									log.info("baos:---createPDF");

								} else {
									mailService.sendEmail(name);
									continue;
								}
								if (emailInput.isEmpty()) {
									mailService.sendEmail(baos, name, gmail,
											paySlipDetails.get(Util.MONTH) + " " + paySlipDetails.get(Util.YEAR));

									MonthlySalaryDetails saveMonthlySalaryDetails = new MonthlySalaryDetails();

									saveMonthlySalaryDetails(saveMonthlySalaryDetails, salaryDetails, paySlipDetails,
											paySlip);
								}
							}

						} catch (Exception e) {
							e.printStackTrace();
							mailService.sendEmail(name);
							log.info("e.printStackTrace()---" + e.getMessage());
							continue;
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
					log.info("e.printStackTrace()----" + e.getMessage());
					break;
				}
			}

			if (!emailInput.isEmpty() && !emailInput.isBlank()) {
				mailService.sendEmail(payslip, "Hr", emailInput,
						paySlipDetails.get(Util.MONTH) + " " + paySlipDetails.get(Util.YEAR));
			}
		}
		return "Mail Send Successfully";
	}

	private void saveMonthlySalaryDetails(MonthlySalaryDetails saveMonthlySalaryDetails, SalaryDetails salaryDetails,
			Map<String, String> paySlipDetails, PaySlip paySlip) {
		try {
			log.info(
					"PayRollServiceImpl: generatePaySlipForAllEmployees: saveMonthlySalaryDetails info level log message");
			 SimpleDateFormat f = new SimpleDateFormat("dd-MM-yyyy");
			 Calendar cal = Calendar.getInstance();
			 String date = f.format(cal.getTime());
			saveMonthlySalaryDetails.setEmpId(salaryDetails.getEmpId());
			saveMonthlySalaryDetails.setBasic(salaryDetails.getBasic());
			saveMonthlySalaryDetails.setEmployeeESICAmount(salaryDetails.getEmployeeESICAmount());
			saveMonthlySalaryDetails.setEmployerESICAmount(salaryDetails.getEmployerESICAmount());
			saveMonthlySalaryDetails.setEmployeePFAmount(salaryDetails.getEmployeePFAmount());
			saveMonthlySalaryDetails.setEmployerPFAmount(salaryDetails.getEmployerPFAmount());
			saveMonthlySalaryDetails.setMedicalInsurance(salaryDetails.getMedicalInsurance());
			saveMonthlySalaryDetails.setTds(salaryDetails.getTds());
			saveMonthlySalaryDetails.setGrossSalary(paySlip.getGrossSalary().doubleValue());
			saveMonthlySalaryDetails.setNetSalary(paySlip.getNetSalaryAmount());
			saveMonthlySalaryDetails.setAdhoc(salaryDetails.getAdhoc());
			saveMonthlySalaryDetails.setAdjustment(salaryDetails.getAdjustment());
			saveMonthlySalaryDetails.setHouseRentAllowance(salaryDetails.getHouseRentAllowance());
			saveMonthlySalaryDetails.setDearnessAllowance(salaryDetails.getDearnessAllowance());
			saveMonthlySalaryDetails.setGrossDeduction(paySlip.getGrossDeduction());
			saveMonthlySalaryDetails.setAbsentDeduction(paySlip.getLeaveDeductionAmount());
			saveMonthlySalaryDetails.setCreditedDate(date);		
			saveMonthlySalaryDetails.setMonth(paySlipDetails.get(Util.MONTH));
			saveMonthlySalaryDetails.setBonus(salaryDetails.getBonus());
			saveMonthlySalaryDetails.setPresentDays(paySlip.getYouWorkingDays());
			saveMonthlySalaryDetails.setAbsentDays(paySlip.getNumberOfLeavesTaken());
			saveMonthlySalaryDetails.setTotalWorkingDays(paySlip.getTotalWorkingDays());
			saveMonthlySalaryDetails.setHalfDay(paySlip.getHalfday());
			saveMonthlySalaryDetails.setPaidLeave(paySlip.getPaidLeave());
			saveMonthlySalaryDetails.setUnpaidLeave(paySlip.getUnpaidLeave());

			monthlySalaryDetailsRepo.save(saveMonthlySalaryDetails);

		} catch (Exception e) {
			e.printStackTrace();
			log.error(
					"PayRollServiceImpl: generatePaySlipForAllEmployees: saveMonthlySalaryDetails: e.printStackTrace()---"
							+ e.getMessage());
		}
	}

	// null checks for values
	private boolean isNotNull(int employee_id, String name, String gmail, double basic, double grossSalary, double hRA,
			double employerPF, double employeePF, String accountNumber, String bankName, String designation,
			String joiningDate, int officeTotalWorkingDay) {

		if (employee_id == 0 || name.isEmpty() || name == null || officeTotalWorkingDay < 0 || bankName.isEmpty()
				|| bankName == null || accountNumber == null || accountNumber.isEmpty() || designation.isEmpty()
				|| designation == null || joiningDate.isEmpty() || joiningDate == null || basic <= 0
				|| (gmail.isEmpty() || gmail == null) || grossSalary <= 0 || hRA <= 0 || employerPF <= 0
				|| employeePF <= 0) {
			log.info("Salary values are null.");
			return false;
		}
		return true;
	}

	// Leave and leave deduction calculation
	private double calculateAndUpdateEmployeeTotalLeaves(int empId, double empGrossSalary, String month, String year,
			int officeTotalWorkingDay, PaySlip paySlip) throws ParseException, IOException {

		double absentDeductionAmt = 0, halfDayAmount = 0, halfDayAmountDeduct = 0, totalLeaveDeduction = 0;
		try {
			double amountPerDay = empGrossSalary / officeTotalWorkingDay;
			int empRemainingLeave = 0;
			int empPaidLeave = 0;
			int empUnpaidLeave = 0;
			int empTotalWorkingDay = timeSheetRepo.findEmpTotalWorkingDayCount(empId, month, year);

			int empHalfDay = timeSheetRepo.findEmpTotalHalfDayCount(empId, month, year);
			if (empHalfDay > 0) {
				empTotalWorkingDay = empTotalWorkingDay + empHalfDay;
			}
			int empLeave = officeTotalWorkingDay - empTotalWorkingDay;

			Optional<LeaveBalance> leaveBalanceOptional = leaveBalanceRepo.findByEmployeeId(empId);
			if (!leaveBalanceOptional.isPresent()) {
				return -1;
			}

			int leaveBal = leaveBalanceOptional.get().getLeaveBalance();

			if (leaveBal >= empLeave) {
				empRemainingLeave = leaveBal - empLeave;

				leaveBal = empRemainingLeave;
				empPaidLeave = empLeave;
				empUnpaidLeave = 0;

			} else if (empLeave > leaveBal) {
				empRemainingLeave = empLeave - leaveBal;

				empPaidLeave = leaveBal;
				leaveBal = 0;
				empUnpaidLeave = empRemainingLeave;

				absentDeductionAmt = amountPerDay * empUnpaidLeave;
			}

			if (empHalfDay > 0) {
				halfDayAmount = (amountPerDay / 2);
				halfDayAmountDeduct = empHalfDay * halfDayAmount;
			}
			// update Allleaves in db-----------
			leaveBalanceRepo.updateAllLeavesByEmpId(empId, leaveBal, empPaidLeave, empUnpaidLeave, empHalfDay);
			totalLeaveDeduction = halfDayAmountDeduct + absentDeductionAmt;
			paySlip.setNumberOfLeavesTaken(empLeave);
			paySlip.setYouWorkingDays(empTotalWorkingDay);
			paySlip.setPaidLeave(empPaidLeave);
			paySlip.setUnpaidLeave(empUnpaidLeave);
			paySlip.setHalfday(empHalfDay);
			paySlip.setLeaveDeductionAmount(totalLeaveDeduction);
			return totalLeaveDeduction;
		} catch (Exception e) {
			log.error("Error occured while calculating leave & leave deduction " + e.getMessage());
			return -1;
		}
	}

	// gross salary calculation for verification
	private double grossSalaryCalculation(EmpPayrollDetails empPayrollDetails, double fixedBasic,
			SalaryDetails salaryDetails, boolean isESIC, String name) {
		double salary = empPayrollDetails.getSalary();
		double actualBasic = salary / 2;
		double grossSalaryAmount = salary;
		// employer pf and esic portion calculation 13% and 0.75% respectively
		double employerPFAmount = actualBasic * 0.13;
		double employerESICAmount = grossSalaryAmount * 0.0075;

		// employer pf and esic portion calculation 12% and 3.25% respectively
		double employeeESICAmount = grossSalaryAmount * 0.0325;
		String msg = "";
		if (isESIC) {
			if (Math.abs(employerESICAmount - salaryDetails.getEmployerESICAmount()) > 100) {
				msg = "Employer Esic amount different exceeds the difference limit of eSICAmount & fetched employerESICAmount "
						+ Math.abs(employerESICAmount - salaryDetails.getEmployerESICAmount());
			}

			if (Math.abs(employeeESICAmount - salaryDetails.getEmployeeESICAmount()) > 100) {
				msg = "Employee Esic amount different exceeds the difference limit."
						+ Math.abs(employeeESICAmount - salaryDetails.getEmployeeESICAmount());
			}
		}
		if (Math.abs(employerPFAmount - salaryDetails.getEmployerPFAmount()) > 100) {
			msg = "Employer pf amount different exceeds the difference limit."
					+ Math.abs(employerPFAmount - salaryDetails.getEmployerPFAmount());
		}

		if (msg.isEmpty()) {
			if (isESIC) {
				grossSalaryAmount = Math.round(grossSalaryAmount - employerPFAmount
						- (employeeESICAmount + employerESICAmount) + (grossSalaryAmount * 0.01617));

			} else {
				if (fixedBasic <= 15000) {
					grossSalaryAmount = Math
							.round(grossSalaryAmount - (fixedBasic * 0.13) + (grossSalaryAmount * 0.01617));
				} else {
					grossSalaryAmount = Math
							.round(grossSalaryAmount - employerPFAmount + (grossSalaryAmount * 0.01617));
				}
			}
			msg = validateEmployeePF(grossSalaryAmount, salaryDetails.getEmployeePFAmount());
		}
		if (!msg.isEmpty()) {
			mailService.sendEmail(name, msg);
			return -1;
		}
		return grossSalaryAmount;
	}

	private String validateEmployeePF(double calculatedGross, double employeePFAmount) {
		double basic = calculatedGross / 2;
		double empCalcutedPFAmount = basic * 0.12;
		if (Math.abs(empCalcutedPFAmount - employeePFAmount) > 100) {
			return "Employee pf amount different exceeds the difference limit."
					+ Math.abs(empCalcutedPFAmount - employeePFAmount);
		}
		return "";
	}

	@Override
	public SalaryDetailsDTO getEmployeePayrollSalaryDetailsByEmpId(Integer empId) {
		SalaryDetailsDTO salaryDetailsDTO = new SalaryDetailsDTO();
		Optional<EmpPayrollDetails> empPayrollOptional = empPayrollDetailsRepo.findByEmployeeId(empId);
		Optional<SalaryDetails> salaryDetailsOptional = salaryDetailsRepo.findByEmployeeId(empId);
		salaryDetailsDTO.setEmpId(empId);
		try {
			if (empPayrollOptional.isPresent()) {

				salaryDetailsDTO.setSalary(empPayrollOptional.get().getSalary());
				salaryDetailsDTO.setBankName(empPayrollOptional.get().getBankName());
				salaryDetailsDTO.setDesignation(empPayrollOptional.get().getDesignation());
				salaryDetailsDTO.setJoinDate(empPayrollOptional.get().getJoinDate());
				salaryDetailsDTO.setAccountNumber(empPayrollOptional.get().getAccountNumber());
				salaryDetailsDTO.setIfscCode(empPayrollOptional.get().getIfscCode());

				if (salaryDetailsOptional.isPresent()) {

					salaryDetailsDTO.setBasic(salaryDetailsOptional.get().getBasic());
					salaryDetailsDTO.setHouseRentAllowance(salaryDetailsOptional.get().getHouseRentAllowance());
					salaryDetailsDTO.setEmployeeESICAmount(salaryDetailsOptional.get().getEmployeeESICAmount());
					salaryDetailsDTO.setEmployerESICAmount(salaryDetailsOptional.get().getEmployerESICAmount());
					salaryDetailsDTO.setEmployeePFAmount(salaryDetailsOptional.get().getEmployeePFAmount());
					salaryDetailsDTO.setEmployerPFAmount(salaryDetailsOptional.get().getEmployerPFAmount());
					salaryDetailsDTO.setMedicalInsurance(salaryDetailsOptional.get().getMedicalInsurance());
					salaryDetailsDTO.setGrossSalary(salaryDetailsOptional.get().getGrossSalary());
					salaryDetailsDTO.setNetSalary(salaryDetailsOptional.get().getNetSalary());
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return salaryDetailsDTO;
	}

}