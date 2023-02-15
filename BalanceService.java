package pc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pc.aspect.ExceptionAdvice;
import pc.dao.*;
import pc.dto.*;
import pc.entity.*;
import pc.utils.*;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import pc.utils.TemporaryFileFactory;

import javax.servlet.http.HttpServletResponse;
import javax.transaction.Transactional;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;

import static pc.utils.Utils.*;

@Service
public class BalanceService {

    private final TransactionContractAccountSumDao transactionContractAccountSumDao;
    private final TransactionContractAccountSumDateDao transactionContractAccountSumDateDao;
    private final ContractAccountChangeSumDao contractAccountChangeSumDao;
    private final ContractDao contractDao;
    private final ContractAccountCreditDao contractAccountCreditDao;
    private final TransactionContractAccountSumDateTypeDao transactionContractAccountSumDateTypeDao;
    private final TransactionContractAccountSumDateTypeDetailDao transactionContractAccountSumDateTypeDetailDao;
    private final ObjectMapper objectMapper;

    @Autowired
    public BalanceService(TransactionContractAccountSumDao transactionContractAccountSumDao, TransactionContractAccountSumDateDao transactionContractAccountSumDateDao, ContractAccountChangeSumDao contractAccountChangeSumDao, ContractDao contractDao, ContractAccountCreditDao contractAccountCreditDao, TransactionContractAccountSumDateTypeDao transactionContractAccountSumDateTypeDao, TransactionContractAccountSumDateTypeDetailDao transactionContractAccountSumDateTypeDetailDao, ObjectMapper objectMapper) {
        this.transactionContractAccountSumDao = transactionContractAccountSumDao;
        this.transactionContractAccountSumDateDao = transactionContractAccountSumDateDao;
        this.contractAccountChangeSumDao = contractAccountChangeSumDao;
        this.contractDao = contractDao;
        this.contractAccountCreditDao = contractAccountCreditDao;
        this.transactionContractAccountSumDateTypeDao = transactionContractAccountSumDateTypeDao;
        this.transactionContractAccountSumDateTypeDetailDao = transactionContractAccountSumDateTypeDetailDao;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public BalanceDTO getBalance(Map<String, String> param, User user) {

        BalanceDTO balanceDTO = new BalanceDTO();
        Calendar dateEnd = Calendar.getInstance();
        int stateInt = Integer.parseInt(param.get("state"));
        String date = param.get("date");

        Map<Long, BigDecimal> sumTransactionsBefore = transactionContractAccountSumDao.getSum(null, dateParseToCalendar(date), user, stateInt);
        Map<Long, BigDecimal> sumTransactionsAfter = transactionContractAccountSumDao.getSum(dateParseToCalendar(date), dateEnd, user, stateInt);
        Map<Long, BigDecimal> sumPaymentsBefore = contractAccountChangeSumDao.getSumDate(null, dateParseToCalendar(date), user, stateInt);
        Map<Long, BigDecimal> sumPaymentsAfter = contractAccountChangeSumDao.getSumDate(dateParseToCalendar(date), dateEnd, user, stateInt);

        Map<Integer, BalanceDTO.ContactAccountDTO.Total> totalOfContactAccountDTOs = new LinkedHashMap<>();

        for (Contract contract : contractDao.getContracts(user, stateInt)) {
            BalanceDTO.ContractDTO contractDTO = new BalanceDTO.ContractDTO();
            contractDTO.setId(contract.getId());
            contractDTO.setName(contract.getName());
            contractDTO.setNumber(contract.getContractNumber());

            for (ContractAccount contractAccount : contract.getContractAccounts()) {
                Long id = contractAccount.getId();
                BigDecimal stb = sumTransactionsBefore.containsKey(id) ? sumTransactionsBefore.get(id) : BigDecimal.valueOf(0);
                BigDecimal sta = sumTransactionsAfter.containsKey(id) ? sumTransactionsAfter.get(id) : BigDecimal.valueOf(0);
                BigDecimal spb = sumPaymentsBefore.containsKey(id) ? sumPaymentsBefore.get(id) : BigDecimal.valueOf(0);
                BigDecimal spa = sumPaymentsAfter.containsKey(id) ? sumPaymentsAfter.get(id) : BigDecimal.valueOf(0);
                String purseName = contractAccount.getPurse().getName();
                int purseId = contractAccount.getPurse().getId();

                BalanceDTO.ContactAccountDTO contactAccountDTO = new BalanceDTO.ContactAccountDTO(id, purseName, stb, sta, spb, spa,
                        contractAccountCreditDao.getSum(id), contractAccount.getOverdraft(), contractAccount.getMinimalAmount());

                BalanceDTO.ContactAccountDTO.Total contactAccountDTOTotal = new BalanceDTO.ContactAccountDTO.Total(purseName, stb, sta, spb, spa,
                        contractAccountCreditDao.getSum(id), contractAccount.getOverdraft(), contractAccount.getMinimalAmount());

                if (!totalOfContactAccountDTOs.containsKey(purseId)) {
                    totalOfContactAccountDTOs.put(purseId, contactAccountDTOTotal);
                } else {
                    BalanceDTO.ContactAccountDTO.Total lastContactAccountDTOTotal = totalOfContactAccountDTOs.get(purseId);

                    totalOfContactAccountDTOs.put(purseId,
                            new BalanceDTO.ContactAccountDTO.Total(
                                    purseName,
                                    lastContactAccountDTOTotal.getSumTransactionsBefore().add(stb),
                                    lastContactAccountDTOTotal.getSumTransactionsAfter().add(sta),
                                    lastContactAccountDTOTotal.getSumPaymentsBefore().add(spb),
                                    lastContactAccountDTOTotal.getSumPaymentsAfter().add(spa),
                                    lastContactAccountDTOTotal.getCredits() + contractAccountCreditDao.getSum(id),
                                    lastContactAccountDTOTotal.getOverdrafts() + contractAccount.getOverdraft(),
                                    lastContactAccountDTOTotal.getMinimalAmounts() + contractAccount.getMinimalAmount()
                            ));
                }
                contractDTO.addContractAccountDTOs(contactAccountDTO);
            }
            balanceDTO.addContracts(contractDTO);
        }

        for (Integer key : totalOfContactAccountDTOs.keySet()) {
            balanceDTO.addTotal(totalOfContactAccountDTOs.get(key));
        }

        return balanceDTO;
    }

    @Transactional
    public BalanceDetailsDTO[] getBalanceDetails(Map<String, String> param, User user) {

        List<BalanceDetailsDTO> balanceDetailsDTOs = new ArrayList<>();

        String lang = param.get("lang");
        String filterTransactionType = param.get("filterTransactionType");
        String dateDetails = param.get("dateDetails").trim();
        String currencyId = param.get("currencyId");
        String typeTransaction = param.get("typeTransaction").trim();
        String filterContract = param.get("filterContract");
        String filterDateStart = param.get("filterDateStart");
        String filterDateEnd = param.get("filterDateEnd");
        BalanceDetailsDTO balanceDetailsDTO = new BalanceDetailsDTO();

        if (dateDetails.trim().equals("")) {
            if (Integer.parseInt(filterTransactionType) == 1) {
                List<TransactionContractAccountSumDate> list = transactionContractAccountSumDateDao
                        .getSum(dateParseToCalendar(filterDateStart), dateParseToCalendar(filterDateEnd), user, filterContract);

                for (TransactionContractAccountSumDate transactionContractAccountSum : list) {
                    balanceDetailsDTO = new BalanceDetailsDTO();
                    balanceDetailsDTO.setDate(Utils.getDateString(transactionContractAccountSum.getTerminalDateTime()));
                    balanceDetailsDTO.setAmount(transactionContractAccountSum.getAmountChange());
                    balanceDetailsDTO.setCurrency(LanguageMapping.getPurse(transactionContractAccountSum.getContractAccount().getPurse().getName(), lang));
                    balanceDetailsDTO.setCurrencyId(transactionContractAccountSum.getContractAccount().getPurse().getId());
                    balanceDetailsDTO.setLeaf(false);
                    balanceDetailsDTO.setExpanded(false);

                    balanceDetailsDTOs.add(balanceDetailsDTO);
                }
            } else if (Integer.parseInt(filterTransactionType) == 2) {
                List<ContractAccountChangeSum> list = contractAccountChangeSumDao
                        .getSumDate(dateParseToCalendar(filterDateStart), dateParseToCalendar(filterDateEnd), user, filterContract);

                for (ContractAccountChangeSum contractAccountChangeSum : list) {
                    balanceDetailsDTO = new BalanceDetailsDTO();
                    balanceDetailsDTO.setDate(Utils.getDateString(contractAccountChangeSum.getChangeDate()));
                    balanceDetailsDTO.setAmount(contractAccountChangeSum.getAmountChange());
                    balanceDetailsDTO.setCurrency(LanguageMapping.getPurse(contractAccountChangeSum.getContractAccount().getPurse().getName(), lang));
                    balanceDetailsDTO.setCurrencyId(contractAccountChangeSum.getContractAccount().getPurse().getId());
                    balanceDetailsDTO.setLeaf(false);
                    balanceDetailsDTO.setExpanded(false);

                    balanceDetailsDTOs.add(balanceDetailsDTO);
                }
            }
        } else if (!dateDetails.equals("") && typeTransaction.equals("")) {
            if (Integer.parseInt(filterTransactionType) == 1) {
                List<TransactionContractAccountSumDate> list = transactionContractAccountSumDateTypeDao
                        .getSum(dateParseToCalendar(filterDateStart), dateParseToCalendar(filterDateEnd), user, dateParseToCalendar(dateDetails), filterTransactionType, filterContract, currencyId);
                for (TransactionContractAccountSumDate transactionContractAccountSum : list) {
                    balanceDetailsDTO = new BalanceDetailsDTO();
                    balanceDetailsDTO.setDate("");
                    balanceDetailsDTO.setTypeTransaction(transactionContractAccountSum.getReasonId() == 1 ? "Транзакция" : "Оплата");
                    balanceDetailsDTO.setAmount(transactionContractAccountSum.getAmountChange());
                    balanceDetailsDTO.setCurrency(LanguageMapping.getPurse(transactionContractAccountSum.getContractAccount().getPurse().getName(), lang));
                    balanceDetailsDTO.setCurrencyId(transactionContractAccountSum.getContractAccount().getPurse().getId());
                    balanceDetailsDTO.setLeaf(false);
                    balanceDetailsDTO.setExpanded(false);

                    balanceDetailsDTOs.add(balanceDetailsDTO);
                }
            } else if (Integer.parseInt(filterTransactionType) == 2) {
                List<ContractAccountChangeSum> list = contractAccountChangeSumDao.getSumDateType(dateParseToCalendar(filterDateStart), dateParseToCalendar(filterDateEnd), user, dateParseToCalendar(dateDetails), filterTransactionType, filterContract, currencyId);
                for (ContractAccountChangeSum contractAccountChangeSum : list) {
                    balanceDetailsDTO = new BalanceDetailsDTO();
                    balanceDetailsDTO.setDate(Utils.getDateString(contractAccountChangeSum.getChangeDate()));
                    balanceDetailsDTO.setAmount(contractAccountChangeSum.getAmountChange());
                    balanceDetailsDTO.setTypeTransaction(contractAccountChangeSum.getReasonid() == 1 ? "Транзакция" : "Оплата");
                    balanceDetailsDTO.setCurrency(LanguageMapping.getPurse(contractAccountChangeSum.getContractAccount().getPurse().getName(), lang));
                    balanceDetailsDTO.setCurrencyId(contractAccountChangeSum.getContractAccount().getPurse().getId());
                    balanceDetailsDTO.setLeaf(false);
                    balanceDetailsDTO.setExpanded(false);

                    balanceDetailsDTOs.add(balanceDetailsDTO);
                }
            }
        } else {
            if (Integer.parseInt(filterTransactionType) == 1) {
                List<TransactionContractAccountSumTypeDetailDate> list = transactionContractAccountSumDateTypeDetailDao
                        .getSum(dateParseToCalendar(filterDateStart), dateParseToCalendar(filterDateEnd), user, dateParseToCalendar(dateDetails), typeTransaction.trim().equals("Транзакция") ? 1 : 2, filterTransactionType, filterContract, currencyId);
                for (TransactionContractAccountSumTypeDetailDate transactionContractAccountSumTypeDetailDate : list) {
                    Transaction transaction = transactionContractAccountSumTypeDetailDate.getTransaction();
                    List<TransactionReceipt> transactionReceipts = transaction.getTransactionReceipts();
                    for (TransactionReceipt transactionReceipt : transactionReceipts) {
                        if (transactionReceipt.getPosition() == 1) {
                            balanceDetailsDTO = new BalanceDetailsDTO(
                                    Utils.getTimeString(transactionContractAccountSumTypeDetailDate.getTerminalDateTime()),
                                    transactionReceipt.getAmountRounded().multiply(new BigDecimal(-1)),
                                    LanguageMapping.getPurse(transactionContractAccountSumTypeDetailDate.getContractAccount().getPurse().getName(), lang),
                                    true,
                                    false,
                                    transactionReceipt.getQuantity().multiply(new BigDecimal(-1)),
                                    transaction.getDiscount(),
                                    "Транзакция",
                                    transaction.getCard().getCardNumber().toString(),
                                    transactionContractAccountSumTypeDetailDate.getContractAccount().getContract().getName() + " " +
                                            transactionContractAccountSumTypeDetailDate.getContractAccount().getContract().getContractNumber(),
                                    transaction.getTerminal().getServicePoint().getName(),
                                    transactionReceipt.getGoods().getName()
                            );
                        } else {
                            balanceDetailsDTO = new BalanceDetailsDTO();
                            balanceDetailsDTO.setGoods(transactionReceipt.getGoods().getName());
                            balanceDetailsDTO.setQuantity(transactionReceipt.getQuantity().multiply(new BigDecimal(-1)));
                            balanceDetailsDTO.setAmount(transactionReceipt.getAmountRounded().multiply(new BigDecimal(-1)));
                            balanceDetailsDTO.setLeaf(true);
                            balanceDetailsDTO.setExpanded(false);
                        }
                        balanceDetailsDTOs.add(balanceDetailsDTO);
                    }

                }
            } else if (Integer.parseInt(filterTransactionType) == 2) {
                List<ContractAccountChangeSum> list = contractAccountChangeSumDao
                        .getSumDetailDateType(dateParseToCalendar(filterDateStart), dateParseToCalendar(filterDateEnd), user, dateParseToCalendar(dateDetails), typeTransaction.trim().equals("Транзакция") ? 1 : 2, filterTransactionType, filterContract, currencyId);
                for (ContractAccountChangeSum contractAccountChangeSum : list) {
                    balanceDetailsDTO = new BalanceDetailsDTO();
                    balanceDetailsDTO.setDate(Utils.getTimeString(contractAccountChangeSum.getChangeDate()));
                    balanceDetailsDTO.setTypeTransaction("Оплата");
                    balanceDetailsDTO.setContract(contractAccountChangeSum.getContractAccount().getContract().getName() + " " +
                            contractAccountChangeSum.getContractAccount().getContract().getContractNumber());
                    balanceDetailsDTO.setAmount(contractAccountChangeSum.getAmountChange());
                    balanceDetailsDTO.setCurrency(LanguageMapping.getPurse(contractAccountChangeSum.getContractAccount().getPurse().getName(), lang));
                    balanceDetailsDTO.setCurrencyId(contractAccountChangeSum.getContractAccount().getPurse().getId());
                    balanceDetailsDTO.setLeaf(true);
                    balanceDetailsDTO.setExpanded(false);

                    balanceDetailsDTOs.add(balanceDetailsDTO);
                }
            }
        }
        return balanceDetailsDTOs.toArray(new BalanceDetailsDTO[0]);
    }

    @Transactional
    public void getBalanceDetailsXLSX(Map<String, String> param, String header, HttpServletResponse response) {

        String dateStart = param.get("dateStart");
        String dateEnd = param.get("dateEnd");
        String fileName = param.get("fileName");
        File file;

        try (TemporaryFileFactory fileFactory = new TemporaryFileFactory(fileName)) {
            file = fileFactory.getFile();
            String fileNameOut = "Balance_" + dateStart + "-" + dateEnd;

            returnResponseFile(header, response, file, fileNameOut, ".xlsx");
        } catch (Exception e) {
            new ExceptionAdvice().handleException(e);
            e.printStackTrace();
        }
    }

    @Transactional
    public void getBalanceDetailsFromBrowserXLSX(BalanceDetailsFromBrowserXLSXDTO query, HttpServletResponse
            response) {

        File file = null;

        try {
            String lang = query.getLang();
            String date = query.getDate();

            file = new File(TemporaryFileFactory.generateAbsolutePath());

            response.setContentType("application/json; charset=UTF-8");

            Workbook workbook = WorkBoolXLSXFactory.getWorkbook(lang, 9);
            Sheet sheet = workbook.getSheet("Sheet0");
            CellStyle csCell = workbook.getCellStyleAt((short) 2);
            CellStyle csCelln2 = workbook.getCellStyleAt((short) 3);
            CellStyle csCelln3 = workbook.getCellStyleAt((short) 4);
            Row row;

            int iCurRowsOnSheet = 1;
            iCurRowsOnSheet = iterateFromParams(sheet, query.getRows(), iCurRowsOnSheet, csCell, csCelln3, csCelln2);

            row = sheet.createRow(iCurRowsOnSheet);
            Cell cell = row.createCell(0);
            cell.setCellValue(query.getTotalLabel());
            cell.setCellStyle(csCell);

            sheet.createFreezePane(0, 1, 0, 1);

            StringTokenizer stringTokenizer = new StringTokenizer(date, ".");
            String day = stringTokenizer.nextToken();
            if (day.length() == 1) day = "0" + day;
            String month = stringTokenizer.nextToken();
            if (month.length() == 1) month = "0" + month;
            String year = stringTokenizer.nextToken();

            workbook.setSheetName(0, "Balance_" + year + "-" + month + "-" + day);
            PrintSetup printSetup = sheet.getPrintSetup();
            printSetup.setLandscape(true);
            printSetup.setPaperSize(PrintSetup.A4_PAPERSIZE);
            printSetup.setScale((short) 73);
            workbook.write(new FileOutputStream(file));

            Properties properties = new Properties();
            properties.put("fileName", file.getName());

            objectMapper.writeValue(response.getOutputStream(), Arrays.asList(properties));
        } catch (Exception e) {
            new ExceptionAdvice().handleException(e);
            e.printStackTrace();
        } finally {
            if (file != null) file.delete();
        }
    }

    private static int iterateFromParams(
            Sheet sheet,
            BalanceDetailsFromBrowserXLSXDTO.Row[] rows,
            int iCurRowsOnSheet,
            CellStyle csCell,
            CellStyle csCelln3,
            CellStyle csCelln2
    ) {
        for (BalanceDetailsFromBrowserXLSXDTO.Row row : rows) {
            String id = row.getId();

            if (id == null || !id.equals("root")) {
                Row sheetRow = sheet.createRow(iCurRowsOnSheet);
                Cell cell = sheetRow.createCell(0);
                cell.setCellValue(row.getDate());
                cell.setCellStyle(csCell);
                cell = sheetRow.createCell(1);
                cell.setCellValue(row.getTypeTransaction());
                cell.setCellStyle(csCell);
                cell = sheetRow.createCell(2);
                cell.setCellValue(row.getCardNumber());
                cell.setCellStyle(csCell);
                cell = sheetRow.createCell(3);
                cell.setCellValue(row.getContract());
                cell.setCellStyle(csCell);
                cell = sheetRow.createCell(4);
                cell.setCellValue(row.getServicePoint());
                cell.setCellStyle(csCell);
                cell = sheetRow.createCell(5);
                cell.setCellValue(row.getGoods());
                cell.setCellStyle(csCell);
                cell = sheetRow.createCell(6);
                if (row.getQuantity() != 0.0) cell.setCellValue(row.getQuantity());
                cell.setCellStyle(csCelln3);
                cell = sheetRow.createCell(7);
                if (row.getAmount() != 0.0) cell.setCellValue(row.getAmount());
                cell.setCellStyle(csCelln2);
                cell = sheetRow.createCell(8);
                if (row.getDiscount() != 0.0) cell.setCellValue(row.getDiscount());
                cell.setCellStyle(csCelln2);
                cell = sheetRow.createCell(9);
                cell.setCellValue(row.getCurrency());
                cell.setCellStyle(csCell);
                iCurRowsOnSheet++;
            }
            BalanceDetailsFromBrowserXLSXDTO.Row[] childNodes = row.getChildNodes();
            iCurRowsOnSheet = iterateFromParams(sheet, childNodes, iCurRowsOnSheet, csCell, csCelln3, csCelln2);
        }
        return iCurRowsOnSheet;
    }

}
