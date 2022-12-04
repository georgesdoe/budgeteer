package com.georgesdoe.budgeteer.domain.importing.parsing;

import com.georgesdoe.budgeteer.domain.importing.FileImportException;
import com.georgesdoe.budgeteer.domain.importing.ImportedTransaction;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class XlsFileParser implements FileParser {

    RowMap map;

    FileParserConfiguration config;

    static class RowMap {
        Integer timestampIndex, descriptionIndex, categoryIndex, valueIndex;

        public void validate() {
            if (timestampIndex == null) {
                throw new FileImportException("Could not find timestamp field");
            }
            if (valueIndex == null) {
                throw new FileImportException("Could not find value field");
            }
        }
    }

    @Override
    public List<ImportedTransaction> parseFile(MultipartFile file, FileParserConfiguration configuration) {
        config = configuration;
        try (InputStream inp = file.getInputStream()) {
            HSSFWorkbook wb = new HSSFWorkbook(new POIFSFileSystem(inp));
            if (wb.getNumberOfSheets() == 0) {
                throw new FileImportException("No sheets present in file");
            }
            var sheet = wb.getSheetAt(0);

            parseHeader(sheet);
            return parseTransactions(sheet);
        } catch (IOException e) {
            throw new FileImportException(e);
        }
    }

    private void parseHeader(Sheet sheet) {
        var rows = sheet.rowIterator();
        if (!rows.hasNext()) {
            throw new FileImportException("No header present in file");
        }

        var header = rows.next();
        var cells = header.cellIterator();
        var result = new RowMap();

        while (cells.hasNext()) {
            var cell = cells.next();
            if (cell.getCellType() != CellType.STRING) {
                continue;
            }
            if (cell.getStringCellValue().equals(config.getTimestampField())) {
                result.timestampIndex = cell.getColumnIndex();
            }
            if (cell.getStringCellValue().equals(config.getCategoryField())) {
                result.categoryIndex = cell.getColumnIndex();
            }
            if (cell.getStringCellValue().equals(config.getDescriptionField())) {
                result.descriptionIndex = cell.getColumnIndex();
            }
            if (cell.getStringCellValue().equals(config.getValueField())) {
                result.valueIndex = cell.getColumnIndex();
            }
        }
        result.validate();
        this.map = result;
    }

    public List<ImportedTransaction> parseTransactions(Sheet sheet) {
        var rows = sheet.rowIterator();
        // Skip header
        rows.next();
        var transactions = new ArrayList<ImportedTransaction>();
        while (rows.hasNext()) {
            var row = rows.next();
            var transaction = parseRow(row);
            transactions.add(transaction);
        }
        return transactions;
    }

    public ImportedTransaction parseRow(Row row) {
        var transaction = new ImportedTransaction();

        transaction.setValue(getTransactionValue(row));
        transaction.setTimestamp(getTransactionTimestamp(row));
        transaction.setCategory(getTransactionCategory(row));
        transaction.setDescription(getTransactionDescription(row));

        return transaction;
    }

    protected BigDecimal getTransactionValue(Row row) {
        var cell = row.getCell(map.valueIndex);

        if (cell == null || cell.getCellType() != CellType.NUMERIC) {
            throw new FileImportException("Invalid value at row " + row.getRowNum() + " and column " + map.valueIndex);
        }
        var value = cell.getNumericCellValue();
        return BigDecimal.valueOf(value);
    }

    protected Instant getTransactionTimestamp(Row row) {
        var cell = row.getCell(map.timestampIndex);
        if (cell == null || (cell.getCellType() != CellType.STRING && cell.getCellType() != CellType.NUMERIC)) {
            throw new FileImportException(
                    "Invalid timestamp at row " + row.getRowNum() + " and column " + map.valueIndex
            );
        }

        var ts = "";
        if (cell.getCellType() == CellType.NUMERIC) {
            var intPart = Double.valueOf(cell.getNumericCellValue()).intValue();
            ts = String.valueOf(intPart);
        } else {
            ts = cell.getStringCellValue();
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(
                config.getTimestampFormat(),
                Locale.getDefault()
        );
        var parsedTs = formatter.parse(ts);
        if (parsedTs.isSupported(ChronoField.INSTANT_SECONDS)) {
            return Instant.from(parsedTs);
        }

        return LocalDate.from(parsedTs).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    protected String getTransactionCategory(Row row) {
        var cell = row.getCell(map.categoryIndex);

        if (cell == null || cell.getCellType() != CellType.STRING) {
            throw new FileImportException(
                    "Invalid category at row " + row.getRowNum() + " and column " + map.categoryIndex
            );
        }
        return cell.getStringCellValue();
    }

    protected String getTransactionDescription(Row row) {
        var cell = row.getCell(map.descriptionIndex);

        if (cell == null || cell.getCellType() != CellType.STRING) {
            throw new FileImportException(
                    "Invalid description at row " + row.getRowNum() + " and column " + map.descriptionIndex
            );
        }
        return cell.getStringCellValue();
    }
}
