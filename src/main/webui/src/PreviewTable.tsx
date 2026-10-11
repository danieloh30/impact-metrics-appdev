import {useEffect,useState} from 'react';

type Reporting={currentAvailable?:boolean;growthRows:number;retainedRows:number;unresolvedRows:number;overlapRows:number;missingWeightRows:number;exactGrowth:string;impactHours:string;firstMeasuredAt:string;lastMeasuredAt:string;snapshotImportedAt:string;baseline:Record<string,unknown>};
type Preview={reporting?:Reporting;headers:string[];rows:{id:string;cells:Record<string,string>}[];total:number;unfilteredTotal?:number};
type Props={name:string;endpoint:string;load:(path:string)=>Promise<Preview>;onError:(message:string)=>void};
const pageSize=25;

export function PreviewTable({name,endpoint,load,onError}:Props){
 const [{query,offset},setSearch]=useState({query:'',offset:0});
 const [basis,setBasis]=useState('all');
 const [report,setReport]=useState<Reporting>();
 const trailing=name==='updated_90-day.csv';
 const [preview,setPreview]=useState<Preview|null>(null),[loading,setLoading]=useState(true);
 useEffect(()=>{
  let stopped=false;
  setLoading(true);setPreview(null);
  const timer=window.setTimeout(()=>{
   load(`${endpoint}&offset=${offset}&limit=${pageSize}&q=${encodeURIComponent(query)}${trailing?`&basis=${basis}`:''}`)
    .then(data=>{if(!stopped){setPreview(data);setReport(data.reporting);}})
    .catch(error=>{if(!stopped)onError(error.message);})
    .finally(()=>{if(!stopped)setLoading(false);});
  },query?250:0);
  return()=>{stopped=true;window.clearTimeout(timer);};
 },[endpoint,query,offset,basis,trailing,load,onError]);
 const filtered=!!query.trim()||basis!=='all';
 return <section className="section preview-section" aria-label={`${name} preview`}>
  <div className="section-title"><h2>{name} <span className="muted">preview</span></h2>
   <span role="status" aria-live="polite">{loading?'Searching rows…':preview?`${preview.total?offset+1:0}–${Math.min(offset+pageSize,preview.total)} of ${preview.total} ${filtered?`matches (${preview.unfilteredTotal??preview.total} total rows)`:'rows'}`:''}</span>
  </div>
  {trailing&&report&&<aside className="trailing-reporting" aria-label="Trailing metrics interpretation">
   <h3>Measured growth and retained inputs</h3>
   <div className="trailing-reporting-counts"><span><strong>{report.growthRows}</strong> measured delta rows</span><span><strong>{report.retainedRows}</strong> retained input rows</span><span><strong>{report.unresolvedRows}</strong> unresolved rows</span></div>
   <p>Exact measured growth: <strong>{report.exactGrowth}</strong> · Recalculated impact hours: <strong>{report.impactHours}</strong>. Retained inputs are excluded from these growth totals.</p>
   {report.missingWeightRows>0&&<p className="reporting-warning">{report.missingWeightRows} measured rows have no valid weight; their impact hours are not calculated or included.</p>}
   <p className="reporting-warning">{report.currentAvailable===false?<strong>Overlap comparison becomes available when current.csv is generated.</strong>:<strong>{report.overlapRows} trailing rows also match current.csv.</strong>} Do not sum both files as independent activity totals. Current results and trailing lifetime deltas represent different measures.</p>
   <details><summary>Baseline snapshot and measurement times</summary>
    <p>Baseline: {String(report.baseline.historyFilename||'Historical workbook')} · Sheets: {Array.isArray(report.baseline.historySheets)?report.baseline.historySheets.join(', '):'Not recorded'}</p>
    <p>Window: {String(report.baseline.windowStart||'Not recorded')} – {String(report.baseline.windowEnd||'Not recorded')} · Imported: {report.snapshotImportedAt}</p>
    <p>Baseline SHA-256: <code>{String(report.baseline.historySha256||'Not recorded')}</code></p>
    <p>Verified measurements: {report.firstMeasuredAt?`${report.firstMeasuredAt} – ${report.lastMeasuredAt}`:'No measurement timestamp recorded'}. Measurements reflect collection time, which may be after the reporting month.</p>
   </details>
  </aside>}
  <div className="preview-search">
   {trailing&&<label htmlFor="preview-basis">Show rows<select id="preview-basis" value={basis} onChange={event=>{setBasis(event.target.value);setSearch({query,offset:0});}}><option value="all">All trailing rows</option><option value="growth">Measured growth only</option><option value="retained">Retained inputs only</option><option value="unresolved">Unresolved only</option></select></label>}
   <label htmlFor="preview-search-input">Search preview
    <input id="preview-search-input" type="search" value={query} maxLength={200} placeholder="Search author, tool, activity, URL…" aria-describedby="preview-search-help" onChange={event=>setSearch({query:event.target.value,offset:0})}/>
   </label>
   {query&&<button type="button" onClick={()=>setSearch({query:'',offset:0})}>Clear search</button>}
  </div>
  <p id="preview-search-help" className="preview-search-help">Search all columns and pages. Downloads include the complete result.</p>
  <div className="table-scroll" aria-busy={loading}>
   {preview&&<table><thead><tr>{preview.headers.map(header=><th key={header}>{header}</th>)}</tr></thead>
    <tbody>{preview.rows.map(row=><tr key={row.id}>{preview.headers.map(header=><td key={header}>{row.cells[header]||'—'}</td>)}</tr>)}</tbody></table>}
   {!loading&&preview?.total===0&&<p className="preview-empty">{filtered?'No rows match your search. Try another term or clear the search.':'This result has no rows.'}</p>}
   {loading&&<p className="preview-empty">Loading preview…</p>}
  </div>
  <div className="pagination"><button disabled={loading||offset===0} onClick={()=>setSearch({query,offset:Math.max(0,offset-pageSize)})}>← Previous</button>
   <button disabled={loading||!preview||offset+pageSize>=preview.total} onClick={()=>setSearch({query,offset:offset+pageSize})}>Next →</button></div>
 </section>;
}
