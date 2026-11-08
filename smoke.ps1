param([string]$BaseUrl='http://localhost:8080')
$ErrorActionPreference = 'Stop'
$checks = 0
function Call-Api([string]$Method, [string]$Path, $Data=$null, [string]$Token='') {
  $headers = @{'X-Request-Id' = [guid]::NewGuid().ToString()}
  if ($Token) { $headers.Authorization = "Bearer $Token" }
  $parameters = @{Method=$Method; Uri="$BaseUrl$Path"; Headers=$headers; UseBasicParsing=$true; TimeoutSec=20}
  if ($null -ne $Data) { $parameters.ContentType='application/json'; $parameters.Body=($Data | ConvertTo-Json -Depth 8) }
  try {
    $response=Invoke-WebRequest @parameters
    $body=$null
    if ($response.Content) { $body=$response.Content | ConvertFrom-Json }
    return @{Status=[int]$response.StatusCode; Body=$body; Headers=$response.Headers}
  } catch {
    if (-not $_.Exception.Response) { throw }
    $body=$null
    try { $body=$_.ErrorDetails.Message | ConvertFrom-Json } catch { }
    return @{Status=[int]$_.Exception.Response.StatusCode; Body=$body}
  }
}
function Check([bool]$Condition,[string]$Message) {
  if (-not $Condition) { throw "FAILED: $Message" }
  $script:checks++
  Write-Host "PASS: $Message"
}
for ($i=0; $i -lt 90; $i++) {
  $login=Call-Api POST '/api/bookings/user/auth' @{username='admin';password='Admin123!'}
  if ($login.Status -eq 200) { break }
  Start-Sleep -Seconds 1
}
Check ($login.Status -eq 200) 'Admin login through Gateway and Eureka'
$admin=$login.Body.accessToken
$suffix=[guid]::NewGuid().ToString('N').Substring(0,12)
$user=Call-Api POST '/user/register' @{username="smoke-$suffix";password='Smoke123!'}
Check ($user.Status -eq 201) 'Public registration issues a JWT'
$token=$user.Body.accessToken
$other=Call-Api POST '/user/register' @{username="other-$suffix";password='Smoke123!'}
Check ($other.Status -eq 201) 'Second user registration'
Check ((Call-Api GET '/api/hotels').Status -eq 401) 'Anonymous access returns 401'
Check ((Call-Api POST '/api/hotels' @{name='Denied';address='Denied'} $token).Status -eq 403) 'User cannot create hotels'
$hotel=Call-Api POST '/api/hotels' @{name="Smoke $suffix";address='Test address'} $admin
Check ($hotel.Status -eq 201) 'Admin creates hotel'
$roomIds=@()
foreach ($number in @('101','102')) {
  $room=Call-Api POST '/api/rooms' @{hotelId=$hotel.Body.id;number=$number;available=$true} $admin
  Check ($room.Status -eq 201) "Admin creates room $number"
  $roomIds += $room.Body.id
}
$start=(Get-Date).AddDays(20).ToString('yyyy-MM-dd')
$end=(Get-Date).AddDays(23).ToString('yyyy-MM-dd')
$dates="startDate=$start&endDate=$end&hotelId=$($hotel.Body.id)"
$recommended=Call-Api GET "/api/rooms/recommend?$dates" $null $token
Check ($recommended.Status -eq 200 -and @($recommended.Body).Count -eq 2) 'Recommendations contain available rooms'
$request=@{requestId=[guid]::NewGuid().ToString();autoSelect=$true;hotelId=$hotel.Body.id;startDate=$start;endDate=$end}
$booking=Call-Api POST '/api/bookings/booking' $request $token
Check ($booking.Status -eq 201 -and $booking.Body.status -eq 'CONFIRMED') 'Automatic booking confirms'
Check ($booking.Body.roomId -eq $roomIds[0]) 'Equal utilization is resolved by room ID'
$replay=Call-Api POST '/booking' $request $token
Check ($replay.Status -eq 200 -and $replay.Body.id -eq $booking.Body.id) 'Same requestId replays same booking'
$modified=$request.Clone(); $modified.endDate=(Get-Date).AddDays(24).ToString('yyyy-MM-dd')
Check ((Call-Api POST '/booking' $modified $token).Status -eq 409) 'Changed payload with same requestId returns 409'
Check ((Call-Api GET "/booking/$($booking.Body.id)" $null $other.Body.accessToken).Status -eq 404) 'Other users cannot read booking'
$overlap=@{requestId=[guid]::NewGuid().ToString();autoSelect=$false;roomId=$booking.Body.roomId;startDate=$start;endDate=$end}
Check ((Call-Api POST '/booking' $overlap $other.Body.accessToken).Status -eq 409) 'Overlapping reservation returns 409'
$internal=@{requestId=[guid]::NewGuid().ToString();bookingId=1;startDate=$start;endDate=$end}
Check ((Call-Api POST "/api/rooms/$($roomIds[0])/release" $internal $admin).Status -eq 403) 'Gateway blocks internal release even for admin'
$history=Call-Api GET '/api/bookings/bookings?page=0&size=5' $null $token
Check ($history.Status -eq 200 -and $history.Body.totalElements -eq 1) 'Own booking history is paginated'
$stats=Call-Api GET "/api/hotels/statistics?$dates" $null $admin
Check ($stats.Status -eq 200 -and $stats.Body.bookedNights -eq 3) 'Occupancy statistics count booked nights'
Check ((Call-Api DELETE "/booking/$($booking.Body.id)" $null $token).Status -eq 204) 'Cancellation releases room'
Check ((Call-Api DELETE "/booking/$($booking.Body.id)" $null $token).Status -eq 204) 'Cancellation is idempotent'
$after=Call-Api GET "/api/rooms/recommend?$dates" $null $token
Check (@($after.Body).Count -eq 2) 'Room is available after compensation'

# Start all HTTP calls before waiting: this exercises the real Gateway, both services and H2 locks.
Add-Type -AssemblyName System.Net.Http
$client=[System.Net.Http.HttpClient]::new()
$client.Timeout=[TimeSpan]::FromSeconds(30)
$pending=@()
try {
  for ($i=0; $i -lt 8; $i++) {
    $payload=@{requestId=[guid]::NewGuid().ToString();autoSelect=$false;roomId=$roomIds[0];startDate=$start;endDate=$end} | ConvertTo-Json
    $message=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,"$BaseUrl/api/bookings")
    $message.Headers.Authorization=[System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
    $message.Content=[System.Net.Http.StringContent]::new($payload,[System.Text.Encoding]::UTF8,'application/json')
    $pending += @{Message=$message;Task=$client.SendAsync($message)}
  }
  $results=@()
  foreach ($item in $pending) {
    $response=$item.Task.GetAwaiter().GetResult()
    $results += @{Status=[int]$response.StatusCode;Body=($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)}
    $response.Dispose()
    $item.Message.Dispose()
  }
  $winners=@($results | Where-Object Status -eq 201)
  $conflicts=@($results | Where-Object Status -eq 409)
  Check ($winners.Count -eq 1 -and $conflicts.Count -eq 7) 'Eight concurrent bookings yield one confirmation and seven conflicts'
  $nextRequest=@{requestId=[guid]::NewGuid().ToString();autoSelect=$true;hotelId=$hotel.Body.id;startDate=(Get-Date).AddDays(30).ToString('yyyy-MM-dd');endDate=(Get-Date).AddDays(32).ToString('yyyy-MM-dd')}
  $balanced=Call-Api POST '/api/bookings' $nextRequest $token
  Check ($balanced.Status -eq 201 -and $balanced.Body.roomId -eq $roomIds[1]) 'Automatic selection favors the less-used room'
  Check ((Call-Api DELETE "/api/bookings/$($balanced.Body.id)" $null $token).Status -eq 204) 'REST alias cancels the balanced booking'
  Check ((Call-Api DELETE "/api/bookings/$($winners[0].Body.id)" $null $token).Status -eq 204) 'Concurrent winner is cancelled and compensated'
} finally { $client.Dispose() }
Write-Host "Smoke test completed: $checks checks passed."
