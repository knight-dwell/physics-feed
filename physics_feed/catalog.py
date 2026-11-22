"""Everything Physics Feed can follow, to pick from in the app.

JOURNALS  name -> (publisher group, source, open access?)   source = RSS url, or "crossref:ISSN"
NEWS      name -> (group, RSS url)
FIELDS    research field -> {"topics": {topic: [keywords]}, "journals": [...], "arxiv": [...]}
"""

C = "crossref:"

JOURNALS = {
    # ---- APS
    "PRL": ("APS", "http://feeds.aps.org/rss/recent/prl.xml", False),
    "PRB": ("APS", "http://feeds.aps.org/rss/recent/prb.xml", False),
    "PRX": ("APS", "http://feeds.aps.org/rss/recent/prx.xml", True),
    "PRA": ("APS", "http://feeds.aps.org/rss/recent/pra.xml", False),
    "PRE": ("APS", "http://feeds.aps.org/rss/recent/pre.xml", False),
    "PRD": ("APS", "http://feeds.aps.org/rss/recent/prd.xml", False),
    "PRC": ("APS", "http://feeds.aps.org/rss/recent/prc.xml", False),
    "PR Research": ("APS", "http://feeds.aps.org/rss/recent/prresearch.xml", True),
    "PRX Quantum": ("APS", "http://feeds.aps.org/rss/recent/prxquantum.xml", True),
    "PR Materials": ("APS", "http://feeds.aps.org/rss/recent/prmaterials.xml", False),
    "PR Applied": ("APS", "http://feeds.aps.org/rss/recent/prapplied.xml", False),
    "PR Fluids": ("APS", "http://feeds.aps.org/rss/recent/prfluids.xml", False),
    "Rev. Mod. Phys.": ("APS", "http://feeds.aps.org/rss/recent/rmp.xml", False),
    # ---- Nature portfolio
    "Nature": ("Nature", C + "1476-4687", False),
    "Nature Physics": ("Nature", C + "1745-2481", False),
    "Nature Photonics": ("Nature", C + "1749-4893", False),
    "Nature Materials": ("Nature", C + "1476-4660", False),
    "Nature Nanotechnology": ("Nature", C + "1748-3395", False),
    "Nature Reviews Physics": ("Nature", C + "2522-5820", False),
    "Nature Communications": ("Nature", C + "2041-1723", True),
    "Communications Physics": ("Nature", C + "2399-3650", True),
    "npj Quantum Materials": ("Nature", C + "2397-4648", True),
    "npj Quantum Information": ("Nature", C + "2056-6387", True),
    "npj Computational Materials": ("Nature", C + "2057-3960", True),
    "Scientific Reports": ("Nature", C + "2045-2322", True),
    "Light: Science & Applications": ("Nature", C + "2047-7538", True),
    # ---- Science / PNAS
    "Science": ("Science & PNAS", C + "1095-9203", False),
    "Science Advances": ("Science & PNAS", C + "2375-2548", True),
    "PNAS": ("Science & PNAS", C + "1091-6490", False),
    # ---- IOP
    "J. Phys.: Condens. Matter": ("IOP", C + "1361-648X", False),
    "J. Phys. A: Math. Theor.": ("IOP", C + "1751-8121", False),
    "New J. Phys.": ("IOP", C + "1367-2630", True),
    "Rep. Prog. Phys.": ("IOP", C + "1361-6633", False),
    "J. Stat. Mech.": ("IOP", C + "1742-5468", False),
    "J. Phys.: Photonics": ("IOP", C + "2515-7647", True),
    "J. Optics": ("IOP", C + "2040-8986", False),
    "2D Materials": ("IOP", C + "2053-1583", False),
    "EPL": ("IOP", C + "1286-4854", False),
    "J. Phys. Commun.": ("IOP", C + "2399-6528", True),
    "Quantum Sci. Technol.": ("IOP", C + "2058-9565", False),
    "Supercond. Sci. Technol.": ("IOP", C + "1361-6668", False),
    "Mach. Learn.: Sci. Technol.": ("IOP", C + "2632-2153", True),
    # ---- AIP
    "Applied Physics Letters": ("AIP", "https://pubs.aip.org/rss/site_1000017/1000011.xml", False),
    "J. Chem. Phys.": ("AIP", "https://pubs.aip.org/rss/site_1000043/1000024.xml", False),
    "J. Math. Phys.": ("AIP", C + "1089-7658", False),
    "J. Appl. Phys.": ("AIP", C + "1089-7550", False),
    "APL Photonics": ("AIP", C + "2378-0967", True),
    "AIP Advances": ("AIP", C + "2158-3226", True),
    "Chaos": ("AIP", C + "1089-7682", False),
    # ---- Springer
    "Commun. Math. Phys.": ("Springer", C + "1432-0916", False),
    "Lett. Math. Phys.": ("Springer", C + "1573-0530", False),
    "Ann. Henri Poincaré": ("Springer", C + "1424-0661", False),
    "J. Stat. Phys.": ("Springer", C + "1572-9613", False),
    "Eur. Phys. J. B": ("Springer", C + "1434-6036", False),
    "Eur. Phys. J. Plus": ("Springer", C + "2190-5444", False),
    "J. Low Temp. Phys.": ("Springer", C + "1573-7357", False),
    # ---- Elsevier
    "Phys. Rep.": ("Elsevier", C + "0370-1573", False),
    "Annals of Physics": ("Elsevier", C + "1096-035X", False),
    "Physica A": ("Elsevier", C + "1873-2119", False),
    "Physica B": ("Elsevier", C + "1873-2135", False),
    "Physica C": ("Elsevier", C + "1873-2143", False),
    "Phys. Lett. A": ("Elsevier", C + "1873-2429", False),
    "J. Magn. Magn. Mater.": ("Elsevier", C + "1873-4766", False),
    "Nucl. Phys. B": ("Elsevier", C + "0550-3213", True),
    "Comput. Phys. Commun.": ("Elsevier", C + "1879-2944", False),
    # ---- Optica / ACS / RSC / Wiley / Cambridge
    "Optica": ("Optica", C + "2334-2536", True),
    "Optics Express": ("Optica", C + "1094-4087", True),
    "Optics Letters": ("Optica", C + "1539-4794", False),
    "Nano Letters": ("ACS", C + "1530-6992", False),
    "ACS Nano": ("ACS", C + "1936-086X", False),
    "ACS Photonics": ("ACS", C + "2330-4022", False),
    "Soft Matter": ("RSC", C + "1744-6848", False),
    "J. Fluid Mech.": ("Cambridge", C + "1469-7645", False),
    "Advanced Materials": ("Wiley", "https://onlinelibrary.wiley.com/feed/15214095/most-recent", False),
    "Adv. Theory & Simulations": ("Wiley", "https://onlinelibrary.wiley.com/feed/25130390/most-recent", False),
    "Adv. Funct. Mater.": ("Wiley", C + "1616-3028", False),
    "Adv. Quantum Technol.": ("Wiley", C + "2511-9044", False),
    "Laser Photonics Rev.": ("Wiley", C + "1863-8899", False),
    "phys. stat. sol. (b)": ("Wiley", C + "1521-3951", False),
    # ---- open community journals
    "Quantum": ("Open journals", "https://quantum-journal.org/feed/", True),
    "SciPost Physics": ("Open journals", C + "2542-4653", True),
    # ---- Indian
    "Pramana – J. Phys.": ("Indian", C + "0973-7111", False),
    "Indian J. Phys.": ("Indian", C + "0974-9845", False),
    "Current Science": ("Indian", C + "0011-3891", True),
    "J. Indian Inst. Sci.": ("Indian", C + "0019-4964", False),
    "Resonance": ("Indian", C + "0973-712X", False),
    "Bull. Mater. Sci.": ("Indian", C + "0973-7669", False),
    "Proc. INSA": ("Indian", C + "2454-9983", False),
}

NEWS = {
    # ML & AI
    "Google Research blog": ("ML & AI", "https://blog.research.google/feeds/posts/default"),
    "Google DeepMind": ("ML & AI", "https://deepmind.google/blog/rss.xml"),
    "OpenAI news": ("ML & AI", "https://openai.com/news/rss.xml"),
    "Hugging Face blog": ("ML & AI", "https://huggingface.co/blog/feed.xml"),
    "Berkeley AI (BAIR)": ("ML & AI", "https://bair.berkeley.edu/blog/feed.xml"),
    "MIT Tech Review – AI": ("ML & AI", "https://www.technologyreview.com/topic/artificial-intelligence/feed"),
    # physics news
    "Physics (APS)": ("Physics news", "http://feeds.aps.org/rss/recent/physics.xml"),
    "Physics World": ("Physics news", "https://physicsworld.com/feed/"),
    "Quanta Magazine": ("Physics news", "https://www.quantamagazine.org/feed/"),
    "Phys.org – physics": ("Physics news", "https://phys.org/rss-feed/physics-news/"),
    "ScienceDaily – physics": ("Physics news", "https://www.sciencedaily.com/rss/matter_energy/physics.xml"),
    # India
    "The Hindu – Science": ("Indian science", "https://www.thehindu.com/sci-tech/science/feeder/default.rss"),
    "The Wire Science": ("Indian science", "https://science.thewire.in/feed/"),
    "PIB press releases (DST & others)": ("DST / PIB", "https://pib.gov.in/RssMain.aspx?ModId=6&Lang=1&Regid=3"),
}

FIELDS = {
    "Condensed matter theory": {
        "topics": {
            "Strongly correlated": ["strongly correlated", "correlated electron", "heavy fermion", "Mott", "Hubbard"],
            "Quantum magnetism": ["quantum magnet", "spin liquid", "frustrated magnet", "Heisenberg model", "Kitaev",
                                  "altermagnet"],
            "Topological matter": ["topological insulator", "topological phase", "Chern", "Weyl", "quantum Hall",
                                   "Berry phase", "edge state"],
            "Superconductivity (theory)": ["superconduct&pairing", "BCS", "unconventional superconduct",
                                           "Eliashberg", "Bogoliubov"],
            "Many-body methods": ["DMFT", "DMRG", "quantum Monte Carlo", "tensor network", "functional renormalization",
                                  "Green's function", "diagrammatic"],
            "Non-equilibrium & driven": ["Floquet", "quench", "non-equilibrium", "thermalization", "prethermal"],
        },
        "journals": ["PRB", "PRL", "PRX", "PR Research", "J. Phys.: Condens. Matter", "npj Quantum Materials"],
        "arxiv": ["cond-mat.str-el", "cond-mat.mes-hall"],
    },
    "Condensed matter experiment": {
        "topics": {
            "ARPES & spectroscopy": ["ARPES", "photoemission", "Raman", "optical conductivity", "infrared spectroscopy"],
            "STM / STS": ["scanning tunneling", "STM", "STS", "quasiparticle interference"],
            "Neutron & X-ray scattering": ["neutron scattering", "inelastic neutron", "RIXS", "X-ray diffraction",
                                           "resonant X-ray"],
            "Transport & thermodynamics": ["magnetoresistance", "Hall effect", "thermal conductivity", "specific heat",
                                           "quantum oscillation", "de Haas"],
            "Ultrafast & pump-probe": ["ultrafast", "pump-probe", "time-resolved", "femtosecond"],
            "Thin films & growth": ["thin film", "MBE", "molecular beam epitaxy", "heterostructure", "epitaxial"],
            "High pressure": ["high pressure", "diamond anvil"],
            "NMR / μSR": ["NMR", "nuclear magnetic resonance", "muon spin", "μSR", "muSR"],
        },
        "journals": ["PRB", "PRL", "Nature Physics", "Nature Materials", "PR Materials", "Advanced Materials",
                     "Nano Letters", "Applied Physics Letters"],
        "arxiv": ["cond-mat.str-el", "cond-mat.mtrl-sci", "cond-mat.supr-con"],
    },
    "Superconductivity": {
        "topics": {
            "Cuprates": ["cuprate", "YBCO", "LSCO", "Bi2212"],
            "Nickelates & iron-based": ["nickelate", "iron-based superconduct", "pnictide", "FeSe"],
            "Superconductivity": ["superconduct", "Josephson", "Cooper pair", "Meissner", "vortex lattice"],
        },
        "journals": ["PRB", "PRL", "Supercond. Sci. Technol.", "Physica C", "Nature Physics"],
        "arxiv": ["cond-mat.supr-con"],
    },
    "Magnetism & spintronics": {
        "topics": {
            "Spintronics": ["spintronic", "spin-orbit torque", "spin current", "spin Hall", "magnetic tunnel junction"],
            "Magnons & skyrmions": ["magnon", "skyrmion", "spin wave", "magnonic"],
            "Frustration & spin liquids": ["spin liquid", "frustrat", "kagome", "pyrochlore", "spin ice"],
        },
        "journals": ["PRB", "J. Magn. Magn. Mater.", "PR Applied", "Applied Physics Letters"],
        "arxiv": ["cond-mat.str-el", "cond-mat.mtrl-sci"],
    },
    "2D materials & moiré": {
        "topics": {
            "Graphene & moiré": ["graphene", "twisted bilayer", "moiré", "moire", "magic angle"],
            "TMDs & van der Waals": ["transition metal dichalcogenide", "TMD", "MoS2", "WSe2", "van der Waals",
                                     "hBN"],
        },
        "journals": ["2D Materials", "Nano Letters", "ACS Nano", "Nature Nanotechnology", "PRB"],
        "arxiv": ["cond-mat.mes-hall", "cond-mat.mtrl-sci"],
    },
    "Soft matter & biophysics": {
        "topics": {
            "Active matter": ["active matter", "self-propelled", "active particle", "motility", "swimmer"],
            "Colloids & polymers": ["colloid", "polymer", "gel", "rheology", "liquid crystal"],
            "Glasses & granular": ["glass transition", "jamming", "granular", "amorphous", "supercooled"],
            "Biophysics": ["biophysic", "membrane", "protein", "cell mechanics", "DNA"],
        },
        "journals": ["Soft Matter", "PRE", "PR Fluids", "J. Fluid Mech.", "PNAS"],
        "arxiv": ["cond-mat.soft", "physics.bio-ph"],
    },
    "Statistical mechanics": {
        "topics": {
            "Non-equilibrium stat mech": ["non-equilibrium", "nonequilibrium", "stochastic thermodynamics",
                                          "entropy production", "fluctuation theorem", "large deviation"],
            "Phase transitions & criticality": ["phase transition", "critical exponent", "critical phenomena",
                                                "universality class", "renormalization group", "Ising"],
            "Stochastic processes": ["stochastic process", "random walk", "Brownian", "Langevin", "first passage",
                                     "diffusion"],
            "Interface growth & KPZ": ["KPZ", "Kardar-Parisi-Zhang", "interface growth"],
            "Random matrices & disorder": ["random matrix", "spin glass", "replica", "disordered system"],
            "Mpemba & anomalous relaxation": ["Mpemba", "anomalous relaxation"],
        },
        "journals": ["PRE", "J. Stat. Mech.", "J. Stat. Phys.", "Physica A", "J. Phys. A: Math. Theor.", "EPL"],
        "arxiv": ["cond-mat.stat-mech"],
    },
    "AMO & cold atoms": {
        "topics": {
            "Cold atoms": ["ultracold", "cold atom", "optical lattice", "Bose-Einstein condensate", "Feshbach"],
            "Rydberg & trapped ions": ["Rydberg", "trapped ion", "optical tweezer"],
        },
        "journals": ["PRA", "PRL", "PRX Quantum", "New J. Phys."],
        "arxiv": ["cond-mat.quant-gas", "physics.atom-ph"],
    },
    "Quantum information & computing": {
        "topics": {
            "Quantum computing": ["qubit", "quantum computing", "quantum processor", "quantum advantage",
                                  "variational quantum"],
            "Error correction": ["quantum error correction", "surface code", "logical qubit", "fault-tolerant"],
            "Entanglement": ["entanglement", "Bell inequality", "quantum correlation", "entanglement entropy"],
            "Quantum simulation": ["quantum simulation", "quantum simulator", "analog quantum"],
        },
        "journals": ["PRX Quantum", "Quantum", "npj Quantum Information", "Quantum Sci. Technol.", "PRA"],
        "arxiv": ["quant-ph"],
    },
    "Photonics & optics": {
        "topics": {
            "Topological photonics": ["topological photonic", "photonic topological", "valley photonic"],
            "Nanophotonics & metamaterials": ["metamaterial", "metasurface", "nanophotonic", "plasmon"],
            "Lasers & nonlinear optics": ["laser", "nonlinear optic", "frequency comb", "soliton"],
            "Photonics": ["photonic", "waveguide", "optical cavity", "photonic crystal"],
        },
        "journals": ["Optica", "Optics Express", "Optics Letters", "Nature Photonics", "Light: Science & Applications",
                     "ACS Photonics", "Laser Photonics Rev.", "J. Phys.: Photonics", "APL Photonics"],
        "arxiv": ["physics.optics"],
    },
    "Materials & computation": {
        "topics": {
            "DFT & electronic structure": ["DFT", "density functional", "first-principles", "ab initio",
                                           "electronic structure", "band structure"],
            "Phonons & thermal": ["phonon", "thermal conductivity", "thermoelectric", "electron-phonon"],
            "ML potentials & high-throughput": ["machine learning potential", "interatomic potential",
                                                "high-throughput", "materials discovery"],
        },
        "journals": ["npj Computational Materials", "PR Materials", "Comput. Phys. Commun.", "PRB",
                     "Adv. Theory & Simulations"],
        "arxiv": ["cond-mat.mtrl-sci", "physics.comp-ph"],
    },
    "Machine learning for physics": {
        "topics": {
            "ML in physics": ["machine learning", "neural network", "deep learning", "neural quantum state",
                              "physics-informed", "generative model", "transformer"],
        },
        "journals": ["Mach. Learn.: Sci. Technol.", "npj Computational Materials", "PR Research"],
        "arxiv": ["physics.comp-ph", "cs.LG"],
    },
    "Mathematical physics": {
        "topics": {
            "Integrability": ["integrable", "Bethe ansatz", "Yang-Baxter", "exactly solvable"],
            "CFT & operator algebras": ["conformal field theory", "CFT", "operator algebra", "vertex algebra"],
            "Spectral & random matrix theory": ["spectral theory", "random matrix", "Schrödinger operator"],
        },
        "journals": ["Commun. Math. Phys.", "J. Math. Phys.", "Lett. Math. Phys.", "Ann. Henri Poincaré",
                     "J. Phys. A: Math. Theor.", "J. Stat. Phys."],
        "arxiv": ["math-ph"],
    },
    "High energy & gravity": {
        "topics": {
            "Holography & strings": ["holograph", "AdS/CFT", "string theory", "black hole", "gauge theory"],
            "Particle phenomenology": ["dark matter", "neutrino", "Higgs", "beyond the Standard Model"],
        },
        "journals": ["PRD", "Nucl. Phys. B", "PRL"],
        "arxiv": ["hep-th", "hep-ph", "gr-qc"],
    },
}


def spec_of(name):
    """Config entry [url_or_'crossref', filter, issn, open_access] for a catalog journal."""
    group, src, oa = JOURNALS[name]
    if src.startswith(C):
        return ["crossref", False, src[len(C):], oa]
    return [src, False, "", oa]
