package com.example.Programa_heber.ontology;

import jakarta.annotation.PostConstruct;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.ReasonerRegistry;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class Ontology {

    private static final Logger logger = LoggerFactory.getLogger(Ontology.class);
    private Model model; 
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private static final String ONT_PREFIX = "https://dcm.ffclrp.usp.br/lssb/stock-market-ontology#";
    private static final String[] PREGAO_FILES = { "Datasets/dados_novos_anterior.xlsx", "Datasets/dados_novos_atual.xlsx" };
    private static final String INFO_EMPRESAS_FILE = "Templates/Informacoes_Empresas.xlsx";
    private static final String INFERENCE_OUTPUT_FILENAME = "ontology_stock market_B3.ttl";

    @PostConstruct
    public void init() {
        logger.info(">>> INICIANDO Inicialização do Componente Ontology (@PostConstruct)...");
        lock.writeLock().lock();
        try {
           
            deleteInferredModelCache();

            this.model = loadOrCreateInferredModel();
            if (this.model == null || this.model.isEmpty()) {
                throw new IllegalStateException("FALHA CRÍTICA: O modelo RDF não pôde ser carregado ou criado.");
            }
            logger.info("<<< Ontology INICIALIZADA COM SUCESSO. Total de triplas no modelo: {} >>>", this.model.size());
        } catch (Exception e) {
            logger.error("!!!!!!!! FALHA GRAVE E IRRECUPERÁVEL NA INICIALIZAÇÃO DA ONTOLOGY !!!!!!!!", e);
            throw new RuntimeException("Falha crítica ao inicializar a camada de ontologia.", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Model loadOrCreateInferredModel() throws IOException {
        ClassPathResource inferredResource = new ClassPathResource(INFERENCE_OUTPUT_FILENAME);
        
        if (inferredResource.exists() && inferredResource.contentLength() > 0) {
            logger.info("--- Modelo inferido pré-calculado '{}' encontrado. Carregando... ---", INFERENCE_OUTPUT_FILENAME);
            Model dataModel = ModelFactory.createDefaultModel();
            try (InputStream in = inferredResource.getInputStream()) {
                RDFDataMgr.read(dataModel, in, Lang.TURTLE);
            }
            return dataModel; 
        } else {
            logger.warn("--- Modelo inferido '{}' não encontrado ou vazio. Construindo do zero... ---", INFERENCE_OUTPUT_FILENAME);
            Model baseModel = buildBaseModelFromSources();
            validateBaseModelLoad(baseModel.size());
            
            logger.info("--- Criando modelo de inferência (RDFS Reasoner)... ---");
            Reasoner reasoner = ReasonerRegistry.getRDFSReasoner();
            InfModel infModel = ModelFactory.createInfModel(reasoner, baseModel);
            
            long inferredCount = infModel.size() - baseModel.size();
            logger.info("--- Modelo de inferência criado. Base:{}, Inferidas:{}, Total:{} ---", baseModel.size(), Math.max(0, inferredCount), infModel.size());
            
            saveInferredModelToFileSystem(infModel);
            return infModel;
        }
    }

    private Model buildBaseModelFromSources() throws IOException {
        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefix("b3", ONT_PREFIX);
        model.setNsPrefix("rdfs", RDFS.uri);
        model.setNsPrefix("rdf", RDF.uri);
        model.setNsPrefix("xsd", XSDDatatype.XSD + "#");

        loadInformacoesEmpresas(model, INFO_EMPRESAS_FILE);
        for (String filePath : PREGAO_FILES) {
            loadDadosPregaoExcel(model, filePath);
        }
        return model;
    }

    private void loadInformacoesEmpresas(Model model, String resourcePath) throws IOException {
        logger.info(">> Carregando Cadastro de Empresas de: {}", resourcePath);
        try (InputStream excelFile = new ClassPathResource(resourcePath).getInputStream(); Workbook workbook = new XSSFWorkbook(excelFile)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                String nomeEmpresa = getStringCellValue(row.getCell(0));
                String ticker = getStringCellValue(row.getCell(1));
                if (nomeEmpresa == null || ticker == null || nomeEmpresa.isBlank() || ticker.isBlank()) continue;

                String tickerClean = ticker.trim();
                String nomeEmpresaClean = nomeEmpresa.trim();

                Resource vmRes = model.createResource(ONT_PREFIX + tickerClean);
                addStatement(model, vmRes, RDF.type, model.createResource(ONT_PREFIX + "Valor_Mobiliario"));
                addStatement(model, vmRes, RDFS.label, model.createLiteral(tickerClean));
                addStatement(model, vmRes, model.createProperty(ONT_PREFIX, "ticker"), model.createLiteral(tickerClean));

                Resource empresaRes = model.createResource(ONT_PREFIX + normalizarParaURI(nomeEmpresaClean));
                addStatement(model, empresaRes, RDF.type, model.createResource(ONT_PREFIX + "Empresa_Capital_Aberto"));
                addStatement(model, empresaRes, RDFS.label, nomeEmpresaClean, "pt");

                addStatement(model, empresaRes, model.createProperty(ONT_PREFIX + "temValorMobiliarioNegociado"), vmRes);
                
                for (int i = 3; i <= 5; i++) {
                    String setor = getStringCellValue(row.getCell(i));
                    if (setor != null && !setor.isBlank()) {
                        Resource setorRes = model.createResource(ONT_PREFIX + normalizarParaURI(setor.trim()));
                        addStatement(model, setorRes, RDF.type, model.createResource(ONT_PREFIX + "Setor_Atuacao"));
                        addStatement(model, setorRes, RDFS.label, setor.trim(), "pt");
                        addStatement(model, empresaRes, model.createProperty(ONT_PREFIX, "atuaEm"), setorRes);
                    }
                }
            }
        }
    }
    
    private void loadDadosPregaoExcel(Model model, String resourcePath) throws IOException {
         logger.info(">> Carregando Dados de Pregão de: {}", resourcePath);
        try (InputStream excelFile = new ClassPathResource(resourcePath).getInputStream(); Workbook workbook = new XSSFWorkbook(excelFile)) {
            Sheet sheet = workbook.getSheetAt(0);
            SimpleDateFormat rdfDateFormat = new SimpleDateFormat("yyyy-MM-dd");
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue;

                Date dataPregao = getDateCellValue(row.getCell(2));
                String ticker = getStringCellValue(row.getCell(4));
                
                if (ticker == null || !ticker.matches("^[A-Z]{4}\\d{1,2}$") || dataPregao == null) continue;
                
                String tickerTrim = ticker.trim();
                
                
                Resource valorMobiliario = model.createResource(ONT_PREFIX + tickerTrim);
                
                addStatement(model, valorMobiliario, RDF.type, model.createResource(ONT_PREFIX + "Valor_Mobiliario"));
                addStatement(model, valorMobiliario, RDFS.label, model.createLiteral(tickerTrim));

                String dataFmt = rdfDateFormat.format(dataPregao);
                Resource negociadoResource = model.createResource(ONT_PREFIX + tickerTrim + "_Negociado_" + dataFmt.replace("-", ""));
                addStatement(model, negociadoResource, RDF.type, model.createResource(ONT_PREFIX + "Negociado_Em_Pregao"));

               
                addStatement(model, valorMobiliario, model.createProperty(ONT_PREFIX + "negociado"), negociadoResource);

                Resource pregaoResource = model.createResource(ONT_PREFIX + "Pregao_" + dataFmt.replace("-", ""));
                addStatement(model, pregaoResource, RDF.type, model.createResource(ONT_PREFIX + "Pregao"));
                addStatement(model, pregaoResource, model.createProperty(ONT_PREFIX + "ocorreEmData"), model.createTypedLiteral(dataFmt, XSDDatatype.XSDdate));
                addStatement(model, negociadoResource, model.createProperty(ONT_PREFIX + "negociadoDurante"), pregaoResource);
                
            
                addNumericProperty(model, negociadoResource, model.createProperty(ONT_PREFIX, "precoAbertura"), getNumericCellValue(row.getCell(8)));
                addNumericProperty(model, negociadoResource, model.createProperty(ONT_PREFIX, "precoMaximo"), getNumericCellValue(row.getCell(9)));
                addNumericProperty(model, negociadoResource, model.createProperty(ONT_PREFIX, "precoMinimo"), getNumericCellValue(row.getCell(10)));
                addNumericProperty(model, negociadoResource, model.createProperty(ONT_PREFIX, "precoMedio"), getNumericCellValue(row.getCell(11)));
                addNumericProperty(model, negociadoResource, model.createProperty(ONT_PREFIX, "precoFechamento"), getNumericCellValue(row.getCell(12)));
                addNumericProperty(model, negociadoResource, model.createProperty(ONT_PREFIX, "temQuantidade"), getNumericCellValue(row.getCell(14)));
                addNumericProperty(model, negociadoResource, model.createProperty(ONT_PREFIX, "volumeNegociacao"), getNumericCellValue(row.getCell(15)));
            }
        }
    }


    public List<Map<String, String>> executeQuery(String sparqlQuery) {
        lock.readLock().lock();
        try {
            if (this.model == null) { logger.error("Tentativa de executar consulta em um modelo nulo."); return Collections.emptyList(); }
            List<Map<String, String>> resultsList = new ArrayList<>();
            logger.debug("Executando a consulta SPARQL:\n{}", sparqlQuery);
            Query query = QueryFactory.create(sparqlQuery);
            try (QueryExecution qexec = QueryExecutionFactory.create(query, this.model)) {
                ResultSet rs = qexec.execSelect();
                List<String> resultVars = rs.getResultVars();
                while (rs.hasNext()) {
                    QuerySolution soln = rs.nextSolution();
                    Map<String, String> rowMap = new LinkedHashMap<>();
                    for (String varName : resultVars) {
                        RDFNode node = soln.get(varName);
                        String value = "N/A";
                        if (node != null) {
                            if (node.isLiteral()) { value = node.asLiteral().getLexicalForm(); }
                            else { value = node.isAnon() ? node.asResource().getId().toString() : node.toString(); }
                        }
                        rowMap.put(varName, value);
                    }
                    resultsList.add(rowMap);
                }
            }
            logger.info("Consulta retornou {} resultados.", resultsList.size());
            return resultsList;
        } catch (Exception e) {
            logger.error("Erro durante a execução da consulta SPARQL.", e);
            return Collections.emptyList();
        } finally {
            lock.readLock().unlock();
        }
    }

    private String getStringCellValue(Cell cell) {
        if (cell == null) return null;
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        switch (type) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC: 
                if (DateUtil.isCellDateFormatted(cell)) { return null; }
                double numericValue = cell.getNumericCellValue();
                if (numericValue == Math.floor(numericValue)) { return String.valueOf((long) numericValue); }
                return String.valueOf(numericValue);
            default: return null;
        }
    }
    private Date getDateCellValue(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) return cell.getDateCellValue();
        if (cell.getCellType() == CellType.STRING) {
            for (String format : new String[]{"yyyy-MM-dd", "dd/MM/yyyy"}) {
                try { return new SimpleDateFormat(format).parse(cell.getStringCellValue().trim()); } catch (ParseException ignored) {}
            }
        }
        return null;
    }
    private double getNumericCellValue(Cell cell) {
        if (cell == null) return Double.NaN;
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) return cell.getNumericCellValue();
        if (type == CellType.STRING) {
            try { return Double.parseDouble(cell.getStringCellValue().trim().replace(",", ".")); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }
    private String normalizarParaURI(String texto) {
        if (texto == null) return "";
        return Normalizer.normalize(texto.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-zA-Z0-9_ -]", "")
                .replaceAll("\\s+", "_")
                .replaceAll("/", "_");
    }
    
    private void deleteInferredModelCache() {
        try {
            ClassPathResource inferredResource = new ClassPathResource(INFERENCE_OUTPUT_FILENAME);
            if (inferredResource.isFile()) {
                Path path = Paths.get(inferredResource.getURI());
                if (Files.deleteIfExists(path)) { logger.warn("Arquivo de cache da ontologia deletado para forçar reconstrução: {}", path); }
            }
        } catch (Exception e) {
            logger.warn("Não foi possível deletar o arquivo de cache da ontologia. Isso é normal se ele não existir ou se rodando de dentro de um JAR. Erro: {}", e.getMessage());
        }
    }
    
    private void saveInferredModelToFileSystem(InfModel modelToSave) {
        try {
            Path outputPath = Paths.get("target/classes/" + INFERENCE_OUTPUT_FILENAME);
            Files.createDirectories(outputPath.getParent());
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath))) {
                logger.info("Salvando modelo inferido em: {}", outputPath.toAbsolutePath());
                RDFDataMgr.write(out, modelToSave, Lang.TURTLE);
            }
        } catch (IOException e) {
            logger.warn("Não foi possível salvar o modelo inferido em disco no diretório target/classes.", e);
        }
    }
    
    private void addStatement(Model model, Resource s, Property p, RDFNode o) {
        if (s != null && p != null && o != null) {
            model.add(s, p, o);
        }
    }
    
    private void addStatement(Model model, Resource s, Property p, String o, String lang) {
        if (s != null && p != null && o != null && !o.isBlank()) {
            model.add(s, p, model.createLiteral(o, lang));
        }
    }

    private void addNumericProperty(Model model, Resource s, Property p, double value) {
        if (!Double.isNaN(value)) {
            model.add(s, p, model.createTypedLiteral(value));
        }
    }

    private void validateBaseModelLoad(long size) { if (size < 1000) logger.warn("MODELO BASE SUSPEITOSAMENTE PEQUENO ({}) APÓS CARREGAMENTO!", size); }

}