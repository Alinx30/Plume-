import SwiftUI

/// Association avec le PC et état de la synchronisation.
struct SyncView: View {
    @EnvironmentObject private var sync: SyncManager
    @Environment(\.dismiss) private var dismiss

    @State private var scanning = false
    @State private var searched = false
    @State private var found: [FoundPc] = []
    @State private var manual = ""
    @State private var selected: FoundPc?
    @State private var code = ""
    @State private var message: String?
    @State private var working = false

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "fr_FR")
        f.dateStyle = .short
        f.timeStyle = .short
        f.doesRelativeDateFormatting = true
        return f
    }()

    var body: some View {
        NavigationStack {
            Form {
                if sync.isPaired {
                    pairedSection
                } else {
                    pairingSections
                }
            }
            .navigationTitle("Synchroniser avec le PC")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } }
            }
        }
    }

    @ViewBuilder
    private var pairedSection: some View {
        Section {
            Label(sync.status.pcName ?? "PC", systemImage: "desktopcomputer")
                .font(.headline)
            if sync.status.running {
                HStack { ProgressView(); Text("Synchronisation…") }
            } else if let error = sync.status.error {
                Text(error).foregroundColor(.red)
            } else if sync.status.unreachable {
                Text("PC injoignable. Les deux appareils doivent être sur le même Wi-Fi, avec Plume ouvert sur le PC.")
                    .foregroundColor(.orange)
            }
            if sync.status.lastSync > 0 {
                Text("Dernière synchronisation : " + Self.timeFormatter.string(from: Date(timeIntervalSince1970: Double(sync.status.lastSync) / 1000)))
                    .foregroundColor(.secondary)
            }
        } header: {
            Text("Associé à")
        } footer: {
            Text("Vos notes se synchronisent automatiquement quand Plume est ouvert et que le PC est sur le même Wi-Fi. Pour chaque note, la version modifiée le plus récemment l'emporte.")
        }
        Section {
            Button("Synchroniser maintenant") { Task { await sync.syncNow() } }
                .disabled(sync.status.running)
            Button("Dissocier ce PC", role: .destructive) { sync.unpair() }
        }
    }

    @ViewBuilder
    private var pairingSections: some View {
        Section {
            Text("Sur le PC, ouvrez Plume et cliquez sur « Associer mon téléphone ». Un code à 6 chiffres s'affiche.")
        }
        Section {
            Button {
                Task { await scan() }
            } label: {
                HStack {
                    Text(scanning ? "Recherche en cours…" : "Rechercher mon PC")
                    if scanning { Spacer(); ProgressView() }
                }
            }
            .disabled(scanning)
            ForEach(found) { pc in
                Button {
                    selected = pc
                    message = nil
                } label: {
                    HStack {
                        Label(pc.name, systemImage: "desktopcomputer").foregroundColor(.primary)
                        Spacer()
                        if selected == pc { Image(systemName: "checkmark").foregroundColor(.accentColor) }
                    }
                }
            }
            if searched && !scanning && found.isEmpty {
                Text("Aucun PC trouvé. Vérifiez que Plume est ouvert sur le PC et que les deux appareils sont sur le même Wi-Fi. Si l'iPad a demandé l'accès au réseau local, acceptez puis relancez la recherche.")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
        } header: {
            Text("Sur le Wi-Fi")
        }
        Section {
            TextField("ex. 192.168.1.20", text: $manual)
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("Essayer cette adresse") {
                Task {
                    working = true
                    if let pc = await LanDiscovery.probe(manual) {
                        if !found.contains(pc) { found.append(pc) }
                        selected = pc
                        message = nil
                    } else {
                        message = "Aucun PC Plume ne répond à cette adresse."
                    }
                    working = false
                }
            }
            .disabled(manual.trimmingCharacters(in: .whitespaces).isEmpty || working)
        } header: {
            Text("Ou adresse du PC")
        } footer: {
            Text("L'adresse est affichée sur le PC, dans la fenêtre d'association.")
        }
        if let pc = selected {
            Section {
                TextField("123456", text: $code)
                    .keyboardType(.numberPad)
                    .font(.title2.monospacedDigit())
                Button("Associer") {
                    Task {
                        working = true
                        message = await sync.pair(pc, code: code)
                        working = false
                    }
                }
                .disabled(code.trimmingCharacters(in: .whitespaces).count < 6 || working)
            } header: {
                Text("Code affiché sur \(pc.name)")
            }
        }
        if let message {
            Section { Text(message).foregroundColor(.red) }
        }
    }

    private func scan() async {
        scanning = true
        message = nil
        let list = await sync.discover()
        found = list
        if list.count == 1 { selected = list[0] }
        searched = true
        scanning = false
    }
}
