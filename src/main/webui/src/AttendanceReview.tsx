/** Phase 2 validation does not verify that submitted source values reflect real attendance. */
export function AttendanceReview({rows,issues}:{rows:number;issues:number}){
 return <div className="attendance-review" aria-label="In-person attendance review checklist">
  <h3>What to review before approving</h3>
  <p>{issues===0?'No Phase 2 validation issues were detected. ':'Resolve or acknowledge the Phase 2 issues below. '}RUN.md requires your approval to confirm the source attendance and calculations before author/tool attribution.</p>
  {rows===0?<p>No in-person activities were found. Confirm that the workbook contains no eligible meetings, workshops, presentations, or meetups before approving.</p>:<p>Review the {rows} in-person {rows===1?'activity':'activities'} in the preview below:</p>}
  <ul>
   <li>Check activity types, reporting-month dates, and attendee counts against the workbook. Look for missing or duplicate activities.</li>
   <li>Verify Duration (in hours). Blank durations or durations below one hour use a one-hour minimum.</li>
   <li>Check Attendance hours = Views / attendance × max(Duration (in hours), 1). For example, 25 attendees × 2 hours = 50 attendance hours. The monthly RH/IBM Sales User Experiences rollups use their supplied completed total directly; duration is not multiplied again.</li>
   <li>Confirm authors and tools. These totals are not yet divided across authors/tools; that happens in Phase 3. Booth duty remains in follow-up pending verified leads.</li>
  </ul>
  <p className="attendance-review-note">If source values are wrong, correct the workbook and import a new run before approving. Zero issues means validation passed; it does not confirm the accuracy of the submitted attendance.</p>
 </div>;
}
