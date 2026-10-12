import {useState} from 'react';

export type TrendSeries={name:string;values:(string|null)[]};
export type TrendChart={months:string[];series:TrendSeries[];available:boolean[]};
const colors=['#19692b','#0c5571','#299dc1','#bf6b31','#8d3294','#65a645','#ea7846','#545d8b','#9dbb5f','#d046b0','#328a80','#bb9237','#606eb1','#ad4862','#83bede','#557745','#dbad72','#53565a','#6f497d','#ca8ba1','#174b36','#97703c','#4693a5','#948675'];
const amount=(value:number)=>value.toLocaleString(undefined,{maximumFractionDigits:2});
const label=(month:string)=>new Date(`${month}-01T00:00:00Z`).toLocaleDateString('en-US',{month:'short',year:'2-digit',timeZone:'UTC'});
export function ImpactTrendChart({title,data}:{title:string;data:TrendChart}){
 const [table,setTable]=useState(false),[active,setActive]=useState<number|null>(null),[hidden,setHidden]=useState<Set<string>>(new Set());
 const series=data.series.filter(row=>!hidden.has(row.name));
 const width=Math.max(1000,data.months.length*75+110),height=550,left=85,right=40,top=20,bottom=70,plotWidth=width-left-right,plotHeight=height-top-bottom;
 const positive=data.months.map((_,i)=>series.reduce((n,s)=>n+Math.max(0,Number(s.values[i]||0)),0));
 const negative=data.months.map((_,i)=>series.reduce((n,s)=>n+Math.min(0,Number(s.values[i]||0)),0));
 const magnitude=Math.max(...positive,...negative.map(value=>Math.abs(value)),1),raw=magnitude/8,unit=10**Math.floor(Math.log10(raw)),step=[1,2,5,10].find(n=>n*unit>=raw)!*unit;
 const max=Math.ceil(Math.max(...positive,1)/step)*step,min=Math.floor(Math.min(...negative,0)/step)*step,span=max-min;
 const x=(i:number)=>left+i*plotWidth/Math.max(data.months.length-1,1),y=(value:number)=>top+(max-value)*plotHeight/span;
 const upper=data.months.map(()=>0),lower=data.months.map(()=>0);
 const paths=series.map(row=>{
  const bases=row.values.map((value,i)=>Number(value||0)>=0?upper[i]:lower[i]);
  const ends=row.values.map((value,i)=>{const n=Number(value||0);if(n>=0)upper[i]+=n;else lower[i]+=n;return n>=0?upper[i]:lower[i];});
  const segments:number[][]=[];let segment:number[]=[];
  data.months.forEach((_,i)=>{if(data.available[i]&&row.values[i]!==null)segment.push(i);else if(segment.length){segments.push(segment);segment=[];}});if(segment.length)segments.push(segment);
  return {row,path:segments.map(indices=>`M ${indices.map(i=>`${x(i)},${y(ends[i])}`).join(' L ')} L ${[...indices].reverse().map(i=>`${x(i)},${y(bases[i])}`).join(' L ')} Z`).join(' '),color:colors[data.series.indexOf(row)%colors.length]};
 });
 const totals=data.months.map((_,i)=>data.available[i]?data.series.reduce((sum,row)=>sum+Number(row.values[i]||0),0):null);
 function toggle(name:string){setHidden(previous=>{const next=new Set(previous);if(next.has(name))next.delete(name);else next.add(name);return next;});}
 return <article className="impact-trend-chart" aria-label={title}>
  <div className="section-title"><h3>{title}</h3><button type="button" className="text-button" onClick={()=>setTable(!table)}>{table?'Show chart':'Show pivot table'}</button></div>
  <p className="report-axis">Monthly impact hours · {label(data.months[0])} through {label(data.months.at(-1)!)}</p>
  {table?<div className="table-scroll"><table><thead><tr><th scope="col">{title.includes('tool')?'Tool':'Activity'}</th>{data.months.map(month=><th key={month} scope="col">{label(month)}</th>)}</tr></thead><tbody>{data.series.map(row=><tr key={row.name}><th scope="row">{row.name}</th>{row.values.map((value,i)=><td key={data.months[i]}>{value===null?'Unavailable':amount(Number(value))}</td>)}</tr>)}<tr><th scope="row">Total impact hours</th>{totals.map((value,i)=><td key={data.months[i]}>{value===null?'Unavailable':amount(value)}</td>)}</tr></tbody></table></div>:
  <div className="impact-trend-layout"><div className="impact-trend-plot"><div className="trend-scroll"><svg viewBox={`0 0 ${width} ${height}`} style={data.months.length>24?{minWidth:`${width}px`}:undefined} role="img" aria-label={`${title}, stacked area chart. Monthly totals: ${data.months.map((month,i)=>`${label(month)} ${totals[i]===null?'unavailable':amount(totals[i]!)} impact hours`).join('; ')}`}>
   <text transform={`translate(20 ${top+plotHeight/2}) rotate(-90)`} textAnchor="middle">Impact hours</text>
   {Array.from({length:Math.round(span/step)+1},(_,i)=>max-step*i).map(value=><g key={value}><line x1={left} x2={width-right} y1={y(value)} y2={y(value)} stroke="#d7deea"/><text x={left-12} y={y(value)+4} textAnchor="end">{amount(value)}</text></g>)}
   {paths.map(({row,path,color})=><path key={row.name} d={path} fill={color} stroke={color} strokeWidth="1"><title>{row.name}</title></path>)}
   {data.months.map((month,i)=><g key={month}><text x={x(i)} y={height-35} textAnchor="middle">{new Date(`${month}-01T00:00:00Z`).toLocaleDateString('en-US',{month:'long',timeZone:'UTC'})}</text><text x={x(i)} y={height-17} textAnchor="middle">{month.slice(0,4)}</text><rect x={x(i)-plotWidth/Math.max(data.months.length-1,1)/2} y={top} width={plotWidth/Math.max(data.months.length-1,1)} height={plotHeight} fill="transparent" onPointerEnter={()=>setActive(i)} onPointerLeave={()=>setActive(null)}/></g>)}
   {active!==null&&<line x1={x(active)} x2={x(active)} y1={top} y2={top+plotHeight} stroke="#162944" strokeDasharray="4 3"/>}
  </svg></div><label className="trend-month-inspect">Inspect month<select value={active??''} onChange={event=>setActive(event.target.value===''?null:Number(event.target.value))}><option value="">Choose a month</option>{data.months.map((month,i)=><option value={i} key={month}>{label(month)}</option>)}</select></label>{active!==null&&<div className="trend-month-detail" role="status"><strong>{label(data.months[active])} · {totals[active]===null?'Unavailable':`${amount(totals[active]!)} impact hours`}</strong>{series.map(row=><span key={row.name}>{row.name}: {row.values[active]===null?'Unavailable':amount(Number(row.values[active]))}</span>)}</div>}</div>
   <div className="impact-trend-legend" aria-label={`${title} series`}>{data.series.map((row,i)=><button type="button" key={row.name} aria-pressed={!hidden.has(row.name)} onClick={()=>toggle(row.name)}><i style={{background:colors[i%colors.length]}}/>{row.name}</button>)}</div>
  </div>}
  {data.available.some(value=>!value)&&<p className="baseline-notice">Missing monthly data appears as a gap, not zero. Use the table to inspect available values.</p>}
 </article>;
}
