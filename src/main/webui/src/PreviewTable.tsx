import {useEffect,useState} from 'react';

type Preview={headers:string[];rows:{id:string;cells:Record<string,string>}[];total:number;unfilteredTotal?:number};
type Props={name:string;endpoint:string;load:(path:string)=>Promise<Preview>;onError:(message:string)=>void};
const pageSize=25;

export function PreviewTable({name,endpoint,load,onError}:Props){
 const [{query,offset},setSearch]=useState({query:'',offset:0});
 const [preview,setPreview]=useState<Preview|null>(null),[loading,setLoading]=useState(true);
 useEffect(()=>{
  let stopped=false;
  setLoading(true);setPreview(null);
  const timer=window.setTimeout(()=>{
   load(`${endpoint}&offset=${offset}&limit=${pageSize}&q=${encodeURIComponent(query)}`)
    .then(data=>{if(!stopped)setPreview(data);})
    .catch(error=>{if(!stopped)onError(error.message);})
    .finally(()=>{if(!stopped)setLoading(false);});
  },query?250:0);
  return()=>{stopped=true;window.clearTimeout(timer);};
 },[endpoint,query,offset,load,onError]);
 const filtered=!!query.trim();
 return <section className="section preview-section" aria-label={`${name} preview`}>
  <div className="section-title"><h2>{name} <span className="muted">preview</span></h2>
   <span role="status" aria-live="polite">{loading?'Searching rows…':preview?`${preview.total?offset+1:0}–${Math.min(offset+pageSize,preview.total)} of ${preview.total} ${filtered?`matches (${preview.unfilteredTotal??preview.total} total rows)`:'rows'}`:''}</span>
  </div>
  <div className="preview-search">
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
