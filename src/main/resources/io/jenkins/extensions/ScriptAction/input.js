document.addEventListener('DOMContentLoaded', function() {
    const jsonStr = document.getElementById('modelData').textContent.trim();
    let existingModel = {};
    try { existingModel = JSON.parse(jsonStr); } catch(e) { console.error(e); }

    const editor   = document.getElementById('editor');
    const template = document.getElementById('scenario-template').querySelector('.scenario');

    function bindStep(stepElem) {
        stepElem.querySelector('.del-step')
                .addEventListener('click', () => stepElem.remove());
    }

    function bindScenario(scn) {
        scn.querySelector('.add-step')
                .addEventListener('click', () => {
                    const stepsDiv = scn.querySelector('.steps');
                    const newStep  = template.cloneNode(true)
                            .querySelector('.steps > div')
                            .cloneNode(true);
                    newStep.querySelector('input.st-text').value = '';
                    bindStep(newStep);
                    stepsDiv.appendChild(newStep);
                });
        scn.querySelector('.del-scenario')
                .addEventListener('click', () => scn.remove());
        scn.querySelectorAll('.steps > div').forEach(bindStep);
    }

    editor.querySelectorAll('.scenario').forEach(bindScenario);

    document.getElementById('addScenario')
            .addEventListener('click', () => {
                const newScn = template.cloneNode(true);
                newScn.querySelector('input.sc-title').value = '';
                newScn.querySelectorAll('input.st-text').forEach(i => i.value = '');
                editor.appendChild(newScn);
                bindScenario(newScn);
            });

    window.prepareSave = function() {
        const model = {
            title: document.getElementById('scriptTitle').value,
            scenarios: []
        };
        editor.querySelectorAll('.scenario').forEach(scn => {
            const title = scn.querySelector('input.sc-title').value;
            const steps = Array.from(
                    scn.querySelectorAll('input.st-text')
            ).map(i => i.value);
            model.scenarios.push({ title, steps });
        });
        document.getElementById('jsonData').value =
                JSON.stringify(model, null, 2);
    };
});
