import SwiftUI

enum DS {
    static let accent = Color(red: 1.0, green: 0.30, blue: 0.37)
    static let live = Color(red: 1.0, green: 0.27, blue: 0.23)
    static let bg = Color(red: 0.043, green: 0.051, blue: 0.071)
    static let card = Color.white.opacity(0.07)
    static let line = Color.white.opacity(0.10)
    static let dim = Color.white.opacity(0.62)
}

/// Remote image with a soft placeholder.
struct Img: View {
    let url: String?
    var mode: ContentMode = .fill
    var body: some View {
        if let u = url, let url = URL(string: u) {
            AsyncImage(url: url, transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                switch phase {
                case .success(let i): i.resizable().aspectRatio(contentMode: mode)
                default: Rectangle().fill(DS.card)
                }
            }
        } else {
            Rectangle().fill(DS.card)
        }
    }
}

struct SectionHeader: View {
    let title: String
    var subtitle: String? = nil
    var action: (String, () -> Void)? = nil
    var body: some View {
        HStack(alignment: .lastTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.title3.weight(.bold))
                if let s = subtitle { Text(s).font(.footnote).foregroundStyle(DS.dim) }
            }
            Spacer()
            if let (label, run) = action {
                Button(label, action: run).font(.footnote.weight(.semibold)).tint(DS.accent)
            }
        }
        .padding(.horizontal)
    }
}

struct PosterCard: View {
    let title: String
    let image: String?
    var width: CGFloat = 120
    var progress: Double? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                Img(url: image)
                    .frame(width: width, height: width * 1.5)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                if let p = progress, p > 0 {
                    GeometryReader { g in
                        Capsule().fill(DS.accent).frame(width: g.size.width * p, height: 3)
                            .frame(maxHeight: .infinity, alignment: .bottom)
                    }
                    .frame(width: width, height: width * 1.5).clipShape(RoundedRectangle(cornerRadius: 10))
                }
                if image == nil {
                    Text(title).font(.caption).foregroundStyle(DS.dim).padding(8).frame(width: width, height: width * 1.5)
                }
            }
            Text(title).font(.caption.weight(.medium)).lineLimit(1).frame(width: width, alignment: .leading)
        }
    }
}

struct ChannelLogo: View {
    let url: String?
    var size: CGFloat = 44
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color.white.opacity(0.92))
            Img(url: url, mode: .fit).padding(5)
        }
        .frame(width: size, height: size)
    }
}

struct ChannelRow: View {
    let channel: Channel
    @EnvironmentObject var catalog: Catalog
    @EnvironmentObject var store: AppStore
    var body: some View {
        HStack(spacing: 12) {
            ChannelLogo(url: channel.icon)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(channel.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if store.isFavorite(channel) { Image(systemName: "heart.fill").font(.caption2).foregroundStyle(DS.accent) }
                }
                if let p = catalog.now(channel) {
                    Text(p.title).font(.caption).foregroundStyle(DS.dim).lineLimit(1)
                    ProgressView(value: p.progress).tint(DS.accent).scaleEffect(x: 1, y: 0.6, anchor: .center)
                } else {
                    Text(channel.num > 0 ? "CH \(channel.num)" : " ").font(.caption).foregroundStyle(DS.dim)
                }
            }
        }
        .padding(.vertical, 2)
    }
}

/// Summary that shows 4 lines with "Show more".
struct ExpandableText: View {
    let text: String
    @State private var open = false
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(text).font(.subheadline).lineLimit(open ? nil : 4).foregroundStyle(.primary.opacity(0.9))
            if text.count > 180 {
                Button(open ? "Show less" : "Show more") { withAnimation(.easeInOut(duration: 0.2)) { open.toggle() } }
                    .font(.footnote.weight(.semibold)).tint(DS.dim)
            }
        }
    }
}

struct NetworkLabel: View {
    let name: String
    var body: some View {
        HStack(spacing: 4) {
            Circle().fill(networkColor(name)).frame(width: 5, height: 5)
            Text(name).font(.system(size: 10, weight: .medium)).foregroundStyle(DS.dim).lineLimit(1)
        }
    }
}

struct EmptyState: View {
    let icon: String
    let title: String
    let message: String
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 40)).foregroundStyle(DS.dim)
            Text(title).font(.headline)
            Text(message).font(.subheadline).foregroundStyle(DS.dim).multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxWidth: .infinity)
    }
}

extension Date {
    var timeText: String { formatted(date: .omitted, time: .shortened) }
    var dayText: String {
        let cal = Calendar.current
        if cal.isDateInToday(self) { return "Today" }
        if cal.isDateInTomorrow(self) { return "Tomorrow" }
        return formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }
}
