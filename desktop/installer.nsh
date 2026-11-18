; Autorise Plume à recevoir le téléphone sur le réseau local (pare-feu Windows).
!macro customInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Plume"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="Plume" dir=in action=allow program="$INSTDIR\Plume.exe" enable=yes profile=any'
!macroend

!macro customUnInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Plume"'
!macroend
