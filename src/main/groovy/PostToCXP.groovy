import java.util.regex.Pattern

import com.github.scribejava.core.builder.api.DefaultApi10a

import gngs.*
import groovy.util.logging.Log
import htsjdk.samtools.SAMRecord

/**
 * Utility to load analysis results from Ximmer into a CXP API end point
 * <p>
 * Expects to find OAuth credentials in a file called <code>.cxp/credentials</code>
 * which should have the form:
 * 
 * <pre>
 * apiKey:  anapikey
 * apiSecret: theapisecret
 * accessToken: anaccesstoken
 * accessSecret: theaccesssecret
 * </pre>
 * @author Simon Sadedin
 */
@Log
class PostToCXP extends ToolBase {
    private WebService ws
    
    private Ximmer ximmer
    
    private ConfigObject cfg

    @Override
    public void run() {
        log.info "Loading configuration from ${new File(opts.c).absolutePath}"
        cfg = new ConfigSlurper().parse(new File(opts.c).text)
        log.info "Configuration parsed."
       
        ximmer = new Ximmer(cfg, "cnv", false)
        ximmer.initialiseRuns()
            
        ws = new WebService(opts.cxp)
        ws.autoSlash = true
        ws.credentialsPath = ".cxp/credentials"
        File batchDir = new File('.').absoluteFile.parentFile
        String assay = new File(cfg.target_regions).name.replaceAll('.bed$','')
        
        // Step 1: Make sure the BAM files are registered
        this.registerBAMFiles(assay)
        
        // Step 2: Register a new analysis - or can this happen automatically for a result that doesn't have an analysis?
        this.postAnalysis(batchDir, assay)
    }
    
    /**
     * Post details of the analysis (CNV calls and QC results) to the CXP API end point
     * 
     * @param batchDir
     * @param assay
     */
    void postAnalysis(File batchDir, String assay) {
        
        String sequencer = ximmer.bamFiles*.value[0].withIterator { i -> 
            SAMRecord r = i.next()
            return r.readName.tokenize(':')[0].stripMargin('@')
        }
        
        String batchIdentifier = opts.batch?:batchDir.name
        
        // An analysis needs a batch, so create one?
        List batch = (ws / 'batch').get(identifier:batchIdentifier)
        if(batch) {
            println "Found batch $batch"
        }
        else {
            log.info "Creating new batch $batchIdentifier"
            (ws / 'batch').post(
                metadata: [:],
                identifier: batchIdentifier,
                batchDate(batchDir.lastModified())
            )
        }
        
        (ws / 'analysis/import').post(
            identifier: batchDir.absolutePath,
            assay: assay,
            sequencer: sequencer,
            samples: ximmer.bamFiles*.key,
            batch_id: batchIdentifier,
            results: new File(opts.analysis).absolutePath,
            control_samples: [],
            analysis_samples: ximmer.bamFiles*.key,
            qc: new File(opts.qc).absolutePath
        ) 
    }
    
    /**
     * For each BAM file in the Ximmer analysis, register it with CXP
     * 
     * @param assay
     */
    void registerBAMFiles(String assay) {
        for(SAM bam in ximmer.bamFiles*.value) {
           registerBAM(bam, assay)
        }
    }
    
    /**
     * Register the given BAM file with CXP
     * <p>
     * NOTE: sex is inferred from the BAM file itself. 
     *
     * @param bam
     * @param assay
     */
    void registerBAM(SAM bam, String assay) {
        
        String karyoChr = ['1','X','Y']
        Pattern chrStart = ~'^chr'
        Regions karyoRegions = ximmer.targetRegion.grep { it.chr.replaceAll(chrStart,'') in karyoChr } as Regions
        SexKaryotyper karyotyper = new SexKaryotyper(bam, karyoRegions)
        karyotyper.run()
        
        File bamDir = bam.samFile.absoluteFile.parentFile
        File bamBatchDir = bamDir.parentFile
        
        Map data = [
            'fullpath': bam.samFile.absolutePath,
            'sample': bam.samples[0],
            'filetype': '.bam',
            'alt_sample_id': bam.samples[0],
            'sex': karyotyper.sex.toString(),
            'batch': bamBatchDir.name + '_' + bamDir.name,
            'sequencer': 'Test',
            'assay': assay,
            'batch_date': batchDate(bamBatchDir.lastModified()),
            'metadata': [:]
        ]
        
        // Make sure sample exists
        (ws / 'dataasset/create/bam/').post(data)
    }
    
    String batchDate(long timeMs) {
       new Date(timeMs).format('YYYY-MM-dd') 
    }

    static void main(String [] args) {
        cli('PostToCXP -c <Ximmer Config> -cxp <CXP URL> <analysis directory>', args) {
            c 'Ximmer Configuration File', args:1, required: true
            analysis 'The directory of the analysis to import', args:1, required: true
            qc 'The directory containing QC files to import', args:1, required: true
            cxp 'Base URL to CXP server', args:1, required: true
            batch 'CNV calling batch identifier (default: name of current directory', args:1, required: false
        }
    }
}